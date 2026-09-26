package com.ecommerce.oms.fulfillment.service;

import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.fulfillment.api.dto.FulfillmentDtos.ShipmentResponse;
import com.ecommerce.oms.fulfillment.domain.Shipment;
import com.ecommerce.oms.fulfillment.domain.ShipmentStatus;
import com.ecommerce.oms.fulfillment.repository.ShipmentRepository;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.service.OrderLifecycleService;
import com.ecommerce.oms.order.service.OrderQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Warehouse staff operations: the work queue, and moving parcels along.
 *
 * <h2>Authorization</h2>
 * A staff member may only act on shipments in a warehouse they are assigned to. The role check at the
 * controller cannot express that, so it is enforced here — and the queue query filters by warehouse in
 * SQL rather than loading everything and filtering after, so the scope cannot be forgotten.
 *
 * <h2>Order status is derived, not set</h2>
 * With a split allocation an order has several parcels moving independently. The order's status is
 * computed from the <em>least advanced</em> open parcel, so an order is only SHIPPED once every parcel has
 * shipped. Letting each parcel push the order forward would mark a two-parcel order SHIPPED while half of
 * it sat unpacked, and the customer would be told their whole order was on its way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentService {

    private final ShipmentRepository shipmentRepository;
    private final OrderQueryService orderQueryService;
    private final OrderLifecycleService orderLifecycleService;

    // ------------------------------------------------------------------ reads

    /**
     * The caller's work queue.
     *
     * <p>Admins see every warehouse; staff see only theirs. A staff member with no assignment gets an
     * empty page rather than an error — that is a configuration state, not a failure.
     */
    @Transactional(readOnly = true)
    public PageResponse<ShipmentResponse> queueFor(OmsUserPrincipal caller, ShipmentStatus status,
                                                   Pageable pageable) {
        Set<Long> warehouseIds = caller.isAdmin() ? null : caller.getWarehouseIds();

        if (warehouseIds != null && warehouseIds.isEmpty()) {
            log.debug("Staff userId={} has no warehouse assignment; returning an empty queue",
                    caller.getId());
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }

        var page = warehouseIds == null
                ? shipmentRepository.findQueueForWarehouses(allWarehouseIds(), status, pageable)
                : shipmentRepository.findQueueForWarehouses(warehouseIds, status, pageable);

        log.debug("Fulfillment queue for userId={} status={}: {} shipment(s)",
                caller.getId(), status, page.getNumberOfElements());
        return PageResponse.from(page, ShipmentResponse::from);
    }

    @Transactional(readOnly = true)
    public List<ShipmentResponse> forOrder(Long orderId) {
        return shipmentRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(ShipmentResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ writes

    /**
     * Advances one parcel and reconciles the order status.
     *
     * <p>Sequence: authorise, move the parcel, then derive the order status from all parcels. Deriving
     * last means the order can never be ahead of the physical reality it describes.
     */
    @Transactional
    public ShipmentResponse advance(Long shipmentId, ShipmentStatus target, OmsUserPrincipal staff) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> ApiException.notFound("Shipment", shipmentId));

        assertCallerWorksAtWarehouse(shipment, staff);

        ShipmentStatus previous = shipment.getStatus();
        shipment.transitionTo(target);
        shipmentRepository.save(shipment);

        log.info("Shipment {} moved {} -> {} by {} (warehouse {})",
                shipmentId, previous, target, staff.getEmail(), shipment.getWarehouseId());

        reconcileOrderStatus(shipment.getOrderId(), staff);
        return ShipmentResponse.from(shipment);
    }

    /**
     * Brings the order in line with its parcels.
     *
     * <p>Only moves the order when <em>every</em> open parcel has reached at least the target state, and
     * only through transitions the order state machine permits — so this can never invent a status the
     * lifecycle disallows.
     */
    private void reconcileOrderStatus(Long orderId, OmsUserPrincipal staff) {
        List<Shipment> shipments = shipmentRepository.findByOrderIdOrderByIdAsc(orderId);
        List<Shipment> active = shipments.stream().filter(s -> s.getStatus() != ShipmentStatus.CANCELLED).toList();

        if (active.isEmpty()) {
            log.debug("Order {} has no active shipments; leaving its status alone", orderId);
            return;
        }

        // The least advanced parcel decides: an order is SHIPPED only when all of it has shipped.
        ShipmentStatus slowest = active.stream()
                .min(java.util.Comparator.comparingInt(s -> s.getStatus().ordinal()))
                .map(Shipment::getStatus)
                .orElseThrow();

        OrderStatus target = slowest.correspondingOrderStatus();
        if (target == null) {
            log.debug("Order {}: slowest parcel is {}, which has no order-level equivalent",
                    orderId, slowest);
            return;
        }

        Order order = orderQueryService.loadOrder(orderId);
        if (order.getStatus() == target) {
            return;
        }
        if (!com.ecommerce.oms.order.domain.OrderStateMachine.canTransition(order.getStatus(), target)) {
            log.debug("Order {} is {} and cannot move to {}; shipment states are ahead of the order",
                    orderId, order.getStatus(), target);
            return;
        }

        orderLifecycleService.transition(order, target, staff.getEmail(),
                "All parcels reached " + slowest);
    }

    private void assertCallerWorksAtWarehouse(Shipment shipment, OmsUserPrincipal caller) {
        if (caller.isAdmin() || caller.worksAt(shipment.getWarehouseId())) {
            return;
        }
        log.warn("Staff userId={} attempted to act on shipment {} at warehouse {} they are not assigned to",
                caller.getId(), shipment.getId(), shipment.getWarehouseId());
        // 404 rather than 403: confirming the shipment exists would leak another warehouse's workload.
        throw ApiException.notFound("Shipment", shipment.getId());
    }

    /** Admin queue: an empty restriction set would match nothing, so pass a permissive sentinel. */
    private Set<Long> allWarehouseIds() {
        return shipmentRepository.findAll().stream()
                .map(Shipment::getWarehouseId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
