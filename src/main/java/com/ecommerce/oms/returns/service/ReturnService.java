package com.ecommerce.oms.returns.service;

import com.ecommerce.oms.common.config.OmsProperties;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.error.ErrorCode;
import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.domain.OrderLine;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.event.OrderEvents;
import com.ecommerce.oms.order.repository.OrderRepository;
import com.ecommerce.oms.order.service.OrderLifecycleService;
import com.ecommerce.oms.outbox.service.OutboxRecorder;
import com.ecommerce.oms.payment.domain.Payment;
import com.ecommerce.oms.payment.repository.PaymentRepository;
import com.ecommerce.oms.payment.service.RefundService;
import com.ecommerce.oms.pricing.discount.service.DiscountService;
import com.ecommerce.oms.returns.api.dto.ReturnDtos.CreateReturnRequest;
import com.ecommerce.oms.returns.api.dto.ReturnDtos.ReturnLineRequest;
import com.ecommerce.oms.returns.api.dto.ReturnDtos.ReturnResponse;
import com.ecommerce.oms.returns.domain.ReturnRequest;
import com.ecommerce.oms.returns.domain.ReturnStatus;
import com.ecommerce.oms.returns.repository.ReturnRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Returns: request, approve, reject.
 *
 * <h2>The refund arithmetic</h2>
 * A refund must return <b>what the customer actually paid for those units</b>, not their list price. That
 * means reversing, per line, both its apportioned share of the order discount and its own tax — which is
 * exactly why {@code OrderLine} persists those figures at checkout rather than recomputing them here. A
 * refund computed from list price over-refunds every discounted order; one that ignores tax under-refunds
 * every order.
 *
 * <p>The remainder problem is real and is handled in {@link OrderLine#refundValueFor}: returning 1 of 3
 * units of a ₹100 line thrice, each at 33.33, strands a paisa. The final slice of a line therefore takes
 * whatever is left rather than a computed proportion, so successive partial refunds sum to exactly the
 * line total.
 *
 * <h2>Why approval is a separate step</h2>
 * Requesting does not restock or refund anything. Goods have to come back and be looked at first, and
 * auto-approving would put unexamined — possibly damaged — units back into sellable stock, producing an
 * oversell discovered at the packing bench.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnService {

    private final ReturnRequestRepository returnRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final OrderLifecycleService orderLifecycleService;
    private final DiscountService discountService;
    private final OutboxRecorder outboxRecorder;
    private final OmsProperties properties;

    // =================================================================================
    // Request
    // =================================================================================

    /**
     * Records a return request against a delivered order.
     *
     * <p>Validation, in order: the order must be the caller's, delivered, inside the return window, and
     * every requested quantity must be available to return once existing claims are accounted for. All of
     * it happens before anything is written, so a partially valid request is rejected whole rather than
     * half-recorded.
     */
    @Transactional
    public ReturnResponse request(Long orderId, OmsUserPrincipal customer, CreateReturnRequest request) {
        Order order = loadOwned(orderId, customer);

        assertDelivered(order);
        assertWithinReturnWindow(order);

        ReturnRequest returnRequest = ReturnRequest.requested(
                order.getId(), request.reason(), request.comment());

        Money totalRefund = Money.zero(order.getGrandTotal().currency());
        Map<Long, Integer> requestedPerLine = mergeDuplicateLines(request.lines());

        for (Map.Entry<Long, Integer> entry : requestedPerLine.entrySet()) {
            Long orderLineId = entry.getKey();
            int quantity = entry.getValue();

            OrderLine line = order.findLine(orderLineId)
                    .orElseThrow(() -> ApiException.of(ErrorCode.RETURN_NOT_ALLOWED,
                            "Order %s has no line %d".formatted(order.getOrderNumber(), orderLineId)));

            int alreadyClaimed = returnRepository.unitsClaimedForOrderLine(orderLineId);
            int availableToReturn = line.getQuantity() - alreadyClaimed;
            if (quantity > availableToReturn) {
                throw ApiException.of(ErrorCode.RETURN_NOT_ALLOWED,
                        "Cannot return %d unit(s) of %s: %d of %d already returned or pending return"
                                .formatted(quantity, line.getSku(), alreadyClaimed, line.getQuantity()))
                        .with("sku", line.getSku())
                        .with("requested", quantity)
                        .with("availableToReturn", availableToReturn);
            }

            Money alreadyRefunded = Money.of(
                    returnRepository.refundedAmountForOrderLine(orderLineId),
                    order.getGrandTotal().currency());
            Money lineRefund = line.refundValueFor(quantity, alreadyRefunded);

            returnRequest.addLine(orderLineId, quantity, lineRefund);
            totalRefund = totalRefund.plus(lineRefund);

            log.debug("[{}] Return line: {} x{} -> refund {}",
                    order.getOrderNumber(), line.getSku(), quantity, lineRefund);
        }

        ReturnRequest saved = returnRepository.save(returnRequest);

        // Moves the order to RETURN_REQUESTED, which also queues the customer notification.
        orderLifecycleService.transition(order, OrderStatus.RETURN_REQUESTED,
                customer.getEmail(), "Return requested: " + request.reason());

        log.info("[{}] Return {} requested: {} unit(s), reason={}, refund if approved {}",
                order.getOrderNumber(), saved.getId(), saved.totalUnits(),
                request.reason(), totalRefund);

        return ReturnResponse.from(saved);
    }

    // =================================================================================
    // Resolution
    // =================================================================================

    /**
     * Approves a return: restock, refund, settle the order.
     *
     * <p>Order of operations is deliberate. The refund goes <b>first</b>, and only then is stock restocked
     * and the request marked approved. If the gateway refuses, the whole transaction rolls back and the
     * request stays {@code REQUESTED} — a recoverable state someone can retry. Restocking first and then
     * failing to refund would leave the customer out of pocket with their goods already resold.
     *
     * <p>Damaged and defective goods are refunded but <em>not</em> restocked: see
     * {@link com.ecommerce.oms.returns.domain.ReturnReason#isResellable()}.
     */
    @Transactional
    public ReturnResponse approve(Long returnId, OmsUserPrincipal staff, String note) {
        ReturnRequest returnRequest = loadPending(returnId);
        Order order = orderRepository.findWithLinesById(returnRequest.getOrderId())
                .orElseThrow(() -> ApiException.notFound("Order", returnRequest.getOrderId()));
        Payment payment = paymentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> ApiException.businessRule(
                        "Order %s has no payment to refund against".formatted(order.getOrderNumber())));

        Money refundTotal = returnRequest.totalRefund();

        // 1. Money first — the step most likely to fail.
        if (refundTotal.isPositive()) {
            refundService.refundForReturn(order, payment, returnRequest.getId(), refundTotal,
                    "Return %d approved: %s".formatted(returnRequest.getId(), returnRequest.getReason()));
        }

        // 2. Goods back on the shelf, to the warehouse that shipped them, if they are sellable.
        boolean restockable = returnRequest.getReason().isResellable();
        returnRequest.getLines().forEach(returnLine -> {
            OrderLine orderLine = order.findLine(returnLine.getOrderLineId())
                    .orElseThrow(() -> ApiException.notFound("Order line", returnLine.getOrderLineId()));

            order.recordReturnedUnits(orderLine, returnLine.getQuantity());

            if (restockable) {
                refundService.restockLine(order, orderLine, returnLine.getQuantity(),
                        "Return %d restock".formatted(returnRequest.getId()));
            } else {
                log.info("[{}] {} x{} refunded but NOT restocked (reason={} is not resellable)",
                        order.getOrderNumber(), orderLine.getSku(), returnLine.getQuantity(),
                        returnRequest.getReason());
            }
        });

        // 3. Settle the request and the order.
        returnRequest.approve(note);
        returnRepository.save(returnRequest);

        boolean fullyReturned = order.isFullyReturned();
        if (fullyReturned) {
            orderLifecycleService.transition(order, OrderStatus.RETURNED,
                    staff.getEmail(), "All units returned");
            // A wholly returned order should not consume a limited promotion.
            discountService.releaseRedemption(order.getId());
        } else {
            // Partial return: the rest of the order is still delivered and still returnable.
            orderLifecycleService.transition(order, OrderStatus.DELIVERED,
                    staff.getEmail(), "Partial return settled; remaining items unaffected");
        }
        orderRepository.save(order);

        outboxRecorder.record(OrderEvents.RETURN_SETTLED, OrderEvents.AGGREGATE_TYPE, order.getId(),
                new OrderEvents.ReturnSettled(order.getId(), order.getOrderNumber(), order.getUserId(),
                        returnRequest.getId(), refundTotal, returnRequest.totalUnits(), fullyReturned));

        log.info("[{}] Return {} APPROVED by {}: {} unit(s), refunded {}, restocked={}, fullyReturned={}",
                order.getOrderNumber(), returnId, staff.getEmail(), returnRequest.totalUnits(),
                refundTotal, restockable, fullyReturned);

        return ReturnResponse.from(returnRequest);
    }

    /**
     * Rejects a return. Nothing is refunded or restocked, and the order goes back to
     * {@code DELIVERED} so the customer can raise a fresh request rather than being stuck.
     */
    @Transactional
    public ReturnResponse reject(Long returnId, OmsUserPrincipal staff, String note) {
        ReturnRequest returnRequest = loadPending(returnId);
        Order order = orderRepository.findWithLinesById(returnRequest.getOrderId())
                .orElseThrow(() -> ApiException.notFound("Order", returnRequest.getOrderId()));

        returnRequest.reject(note == null ? "Rejected by " + staff.getEmail() : note);
        returnRepository.save(returnRequest);

        if (order.getStatus() == OrderStatus.RETURN_REQUESTED) {
            orderLifecycleService.transition(order, OrderStatus.DELIVERED,
                    staff.getEmail(), "Return rejected: " + returnRequest.getResolutionNote());
        }

        log.info("[{}] Return {} REJECTED by {}: {}",
                order.getOrderNumber(), returnId, staff.getEmail(), returnRequest.getResolutionNote());

        return ReturnResponse.from(returnRequest);
    }

    // =================================================================================
    // Reads
    // =================================================================================

    @Transactional(readOnly = true)
    public List<ReturnResponse> forOrder(Long orderId, OmsUserPrincipal caller) {
        loadOwned(orderId, caller);
        return returnRepository.findByOrderIdOrderByIdDesc(orderId).stream()
                .map(ReturnResponse::from)
                .toList();
    }

    /** Staff and admin queue of returns awaiting a decision. */
    @Transactional(readOnly = true)
    public PageResponse<ReturnResponse> pendingQueue(Pageable pageable) {
        return PageResponse.from(
                returnRepository.findByStatusOrderByIdAsc(ReturnStatus.REQUESTED, pageable),
                ReturnResponse::from);
    }

    @Transactional(readOnly = true)
    public ReturnResponse findById(Long returnId, OmsUserPrincipal caller) {
        ReturnRequest returnRequest = returnRepository.findWithLinesById(returnId)
                .orElseThrow(() -> ApiException.notFound("Return request", returnId));
        // Ownership is checked through the order, so a customer cannot read someone else's return.
        loadOwned(returnRequest.getOrderId(), caller);
        return ReturnResponse.from(returnRequest);
    }

    // =================================================================================
    // Validation helpers
    // =================================================================================

    private Order loadOwned(Long orderId, OmsUserPrincipal caller) {
        if (caller.isAdmin() || caller.hasRole(com.ecommerce.oms.iam.domain.RoleName.ROLE_WAREHOUSE_STAFF)) {
            return orderRepository.findWithLinesById(orderId)
                    .orElseThrow(() -> ApiException.notFound("Order", orderId));
        }
        return orderRepository.findWithLinesByIdAndUserId(orderId, caller.getId())
                .orElseThrow(() -> ApiException.notFound("Order", orderId));
    }

    private ReturnRequest loadPending(Long returnId) {
        ReturnRequest returnRequest = returnRepository.findWithLinesById(returnId)
                .orElseThrow(() -> ApiException.notFound("Return request", returnId));
        if (!returnRequest.isPending()) {
            throw ApiException.illegalTransition(
                    "Return request %d is already %s".formatted(returnId, returnRequest.getStatus()));
        }
        return returnRequest;
    }

    private void assertDelivered(Order order) {
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw ApiException.of(ErrorCode.RETURN_NOT_ALLOWED,
                    "Order %s is %s; only delivered orders can be returned".formatted(
                            order.getOrderNumber(), order.getStatus()))
                    .with("orderStatus", order.getStatus().name());
        }
    }

    /**
     * Enforces the configured return window.
     *
     * <p>Measured from delivery rather than from placement: a parcel that took two weeks to arrive should
     * not eat the customer's return period.
     */
    private void assertWithinReturnWindow(Order order) {
        int windowDays = properties.returns().windowDays();
        Instant deliveredAt = order.getDeliveredAt();

        if (deliveredAt == null) {
            throw ApiException.of(ErrorCode.RETURN_NOT_ALLOWED,
                    "Order %s has no delivery date recorded".formatted(order.getOrderNumber()));
        }

        Instant deadline = deliveredAt.plus(Duration.ofDays(windowDays));
        if (Instant.now().isAfter(deadline)) {
            throw ApiException.of(ErrorCode.RETURN_NOT_ALLOWED,
                    "The %d-day return window for order %s closed on %s".formatted(
                            windowDays, order.getOrderNumber(), deadline))
                    .with("deliveredAt", deliveredAt.toString())
                    .with("returnWindowClosedAt", deadline.toString());
        }
    }

    /** The same line listed twice must be validated against its combined quantity. */
    private Map<Long, Integer> mergeDuplicateLines(List<ReturnLineRequest> lines) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        lines.forEach(line -> merged.merge(line.orderLineId(), line.quantity(), Integer::sum));
        return merged;
    }
}
