package com.ecommerce.oms.fulfillment.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.error.ApiException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A parcel leaving one warehouse for one order.
 *
 * <p>The unique constraint on {@code (order_id, warehouse_id)} is the model of "one parcel per
 * warehouse". It also makes the routing handler naturally idempotent: a retried
 * {@code OrderPlaced} event trying to create the same shipment hits the constraint rather than
 * producing a duplicate pick task.
 *
 * <p>{@code warehouseId} is what scopes the staff work queue. Staff see only shipments in warehouses
 * they are assigned to, which is the ownership half of authorization that a role check cannot express.
 */
@Entity
@Getter
@Table(name = "shipments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseEntity {

    /** Same transition-table approach as the order lifecycle, scoped to one parcel. */
    private static final Map<ShipmentStatus, Set<ShipmentStatus>> ALLOWED = buildTransitions();

    private static Map<ShipmentStatus, Set<ShipmentStatus>> buildTransitions() {
        Map<ShipmentStatus, Set<ShipmentStatus>> allowed = new EnumMap<>(ShipmentStatus.class);
        allowed.put(ShipmentStatus.PENDING, EnumSet.of(ShipmentStatus.PACKED, ShipmentStatus.CANCELLED));
        allowed.put(ShipmentStatus.PACKED, EnumSet.of(ShipmentStatus.SHIPPED, ShipmentStatus.CANCELLED));
        // Once with a carrier, the only remaining move is delivery. A shipped parcel cannot be
        // cancelled: the goods are physically gone and the customer must use the returns flow.
        allowed.put(ShipmentStatus.SHIPPED, EnumSet.of(ShipmentStatus.DELIVERED));
        allowed.put(ShipmentStatus.DELIVERED, EnumSet.noneOf(ShipmentStatus.class));
        allowed.put(ShipmentStatus.CANCELLED, EnumSet.noneOf(ShipmentStatus.class));
        return Map.copyOf(allowed);
    }

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ShipmentStatus status;

    @Column(name = "tracking_number", length = 60)
    private String trackingNumber;

    @Column(name = "packed_at")
    private Instant packedAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static Shipment pending(Long orderId, Long warehouseId) {
        Shipment shipment = new Shipment();
        shipment.orderId = orderId;
        shipment.warehouseId = warehouseId;
        shipment.status = ShipmentStatus.PENDING;
        return shipment;
    }

    /**
     * Advances the parcel, stamping the matching timestamp and minting a tracking number at dispatch.
     *
     * @throws ApiException 409 if the move is not permitted, listing what is
     */
    public void transitionTo(ShipmentStatus target) {
        Set<ShipmentStatus> permitted = ALLOWED.getOrDefault(status, Set.of());
        if (!permitted.contains(target)) {
            String detail = permitted.isEmpty()
                    ? "%s is a terminal shipment state".formatted(status)
                    : "allowed from %s: %s".formatted(status, permitted);
            throw ApiException.illegalTransition(
                    "Cannot move shipment from %s to %s (%s)".formatted(status, target, detail));
        }

        this.status = target;
        Instant now = Instant.now();
        switch (target) {
            case PACKED -> this.packedAt = now;
            case SHIPPED -> {
                this.shippedAt = now;
                if (this.trackingNumber == null) {
                    this.trackingNumber = generateTrackingNumber();
                }
            }
            case DELIVERED -> this.deliveredAt = now;
            default -> {
                // PENDING and CANCELLED carry no dedicated timestamp; updatedAt records the change.
            }
        }
    }

    public boolean canTransitionTo(ShipmentStatus target) {
        return ALLOWED.getOrDefault(status, Set.of()).contains(target);
    }

    public boolean isOpen() {
        return !status.isTerminal();
    }

    private String generateTrackingNumber() {
        return "TRK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    @Override
    public String toString() {
        return "Shipment[orderId=%d, warehouseId=%d, status=%s]".formatted(orderId, warehouseId, status);
    }
}
