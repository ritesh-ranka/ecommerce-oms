package com.ecommerce.oms.fulfillment.service;

import com.ecommerce.oms.fulfillment.domain.Shipment;
import com.ecommerce.oms.fulfillment.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Turns a confirmed order into warehouse work.
 *
 * <p>This runs <b>after</b> the checkout response has been sent, driven by the outbox. That is the point
 * of requirement #3: a customer should not wait for pick tasks to be created, and a failure to create
 * them must not fail a payment that already succeeded.
 *
 * <p>Routing consumes the warehouse split that was <em>already decided</em> during reservation and
 * carried in the event payload. Re-running allocation here would be wrong: stock has moved on since, and
 * the answer could differ from the units actually committed — creating a pick task for a warehouse that
 * never reserved anything.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingService {

    private final ShipmentRepository shipmentRepository;

    /**
     * Creates one shipment per warehouse in the allocation.
     *
     * <p>Idempotent by construction: the {@code (order_id, warehouse_id)} unique constraint plus this
     * existence check mean a redelivered {@code OrderPlaced} event cannot produce duplicate pick tasks.
     * That matters because outbox delivery is at-least-once.
     *
     * @param warehouseQuantities warehouse id to unit count, from the reservation's allocation plan
     * @return the shipments that now exist for this order
     */
    @Transactional
    public List<Shipment> routeOrder(Long orderId, String orderNumber,
                                     Map<Long, Integer> warehouseQuantities) {
        if (warehouseQuantities == null || warehouseQuantities.isEmpty()) {
            log.warn("[{}] No allocation in the event payload; no shipment created", orderNumber);
            return List.of();
        }

        warehouseQuantities.forEach((warehouseId, units) -> {
            if (shipmentRepository.existsByOrderIdAndWarehouseId(orderId, warehouseId)) {
                log.debug("[{}] Shipment for warehouse {} already exists (idempotent replay)",
                        orderNumber, warehouseId);
                return;
            }
            Shipment shipment = shipmentRepository.save(Shipment.pending(orderId, warehouseId));
            log.info("[{}] Shipment {} queued at warehouse {} for {} unit(s)",
                    orderNumber, shipment.getId(), warehouseId, units);
        });

        List<Shipment> shipments = shipmentRepository.findByOrderIdOrderByIdAsc(orderId);
        if (shipments.size() > 1) {
            log.info("[{}] Split fulfillment: {} parcels across warehouses {}",
                    orderNumber, shipments.size(),
                    shipments.stream().map(Shipment::getWarehouseId).toList());
        }
        return shipments;
    }

    /** Voids open parcels when an order is cancelled before dispatch. */
    @Transactional
    public int cancelShipments(Long orderId, String orderNumber) {
        List<Shipment> open = shipmentRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                .filter(Shipment::isOpen)
                .toList();

        open.forEach(shipment -> {
            shipment.transitionTo(com.ecommerce.oms.fulfillment.domain.ShipmentStatus.CANCELLED);
            shipmentRepository.save(shipment);
        });

        if (!open.isEmpty()) {
            log.info("[{}] Cancelled {} open shipment(s)", orderNumber, open.size());
        }
        return open.size();
    }
}
