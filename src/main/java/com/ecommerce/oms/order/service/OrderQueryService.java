package com.ecommerce.oms.order.service;

import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.order.api.dto.OrderDtos.OrderResponse;
import com.ecommerce.oms.order.api.dto.OrderDtos.OrderSummary;
import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.repository.OrderRepository;
import com.ecommerce.oms.payment.domain.Payment;
import com.ecommerce.oms.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order reads, with ownership enforced in the query rather than after it.
 *
 * <p>A customer-facing lookup filters by {@code userId} in SQL, so a request for someone else's order
 * returns empty and becomes a 404. Loading by id and comparing afterwards would work too, but this way
 * the check cannot be forgotten by a future caller, and the response does not distinguish "not yours"
 * from "does not exist" — which would otherwise let anyone probe for valid order ids.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    /**
     * Fetches an order for a specific caller.
     *
     * <p>Admins may read any order; a customer may read only their own. The role check has already
     * happened at the controller — this is the ownership half that a role check cannot express.
     */
    @Transactional(readOnly = true)
    public OrderResponse findForCaller(Long orderId, OmsUserPrincipal caller) {
        Order order = caller.isAdmin()
                ? orderRepository.findWithLinesById(orderId)
                        .orElseThrow(() -> ApiException.notFound("Order", orderId))
                : orderRepository.findWithLinesByIdAndUserId(orderId, caller.getId())
                        .orElseThrow(() -> {
                            log.warn("Order {} not visible to userId={}", orderId, caller.getId());
                            return ApiException.notFound("Order", orderId);
                        });

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        return OrderResponse.from(order, payment);
    }

    @Transactional(readOnly = true)
    public OrderResponse findByOrderNumberForCaller(String orderNumber, OmsUserPrincipal caller) {
        Order order = orderRepository.findWithLinesByOrderNumber(orderNumber)
                .orElseThrow(() -> ApiException.notFound("Order " + orderNumber));

        if (!caller.isAdmin() && !order.getUserId().equals(caller.getId())) {
            log.warn("Order {} not visible to userId={}", orderNumber, caller.getId());
            throw ApiException.notFound("Order " + orderNumber);
        }

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        return OrderResponse.from(order, payment);
    }

    /** A customer's own order history. */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> findMine(Long userId, OrderStatus status, Pageable pageable) {
        Page<Order> page = status == null
                ? orderRepository.findByUserIdOrderByIdDesc(userId, pageable)
                : orderRepository.findByUserIdAndStatusOrderByIdDesc(userId, status, pageable);

        log.debug("Order history for userId={} status={}: {} of {}",
                userId, status, page.getNumberOfElements(), page.getTotalElements());
        return PageResponse.from(page, OrderSummary::from);
    }

    /** Admin view across all customers. */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> findAll(OrderStatus status, Pageable pageable) {
        Page<Order> page = status == null
                ? orderRepository.findAllByOrderByIdDesc(pageable)
                : orderRepository.findByStatusOrderByIdDesc(status, pageable);
        return PageResponse.from(page, OrderSummary::from);
    }

    /** Shared loader for the services that mutate an order. */
    @Transactional(readOnly = true)
    public Order loadOrder(Long orderId) {
        return orderRepository.findWithLinesById(orderId)
                .orElseThrow(() -> ApiException.notFound("Order", orderId));
    }
}
