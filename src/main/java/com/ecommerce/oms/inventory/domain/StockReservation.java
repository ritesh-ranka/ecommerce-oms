package com.ecommerce.oms.inventory.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A time-boxed claim on stock at one warehouse.
 *
 * <p>The {@code expiresAt} field is the safety net for the entire checkout saga: whatever
 * goes wrong between reserving stock and settling payment — a gateway timeout, a killed JVM,
 * a network partition — the sweeper releases this row and the units return to the pool. No
 * failure mode leaks stock permanently, which is what lets checkout hold locks for
 * milliseconds instead of for the duration of a payment call.
 *
 * <p>{@code variantId} and {@code warehouseId} are denormalised copies. They let the return
 * flow restock to the warehouse that actually shipped the unit without joining back through
 * the inventory row, and they keep the reservation readable in isolation.
 *
 * <p>{@code orderId} is a plain column rather than an association: {@code inventory} must not
 * acquire a compile-time dependency on {@code order}, or the two features stop being
 * independently reasonable. It is null for the brief window inside checkout TX-1 between
 * reserving stock and persisting the order.
 */
@Entity
@Getter
@Table(name = "stock_reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation extends BaseEntity {

    /** Correlation handle, the order number. Stable before the order row exists. */
    @Column(name = "reference", nullable = false, length = 60)
    private String reference;

    @Column(name = "order_id")
    private Long orderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    public static StockReservation pending(String reference, InventoryItem item,
                                           int quantity, Instant expiresAt) {
        StockReservation reservation = new StockReservation();
        reservation.reference = reference;
        reservation.inventoryItem = item;
        reservation.variantId = item.getVariant().getId();
        reservation.warehouseId = item.getWarehouse().getId();
        reservation.quantity = quantity;
        reservation.status = ReservationStatus.PENDING;
        reservation.expiresAt = expiresAt;
        return reservation;
    }

    /** Called once the order row exists, inside the same transaction that created it. */
    public void attachToOrder(Long orderId) {
        this.orderId = orderId;
    }

    public void markCommitted() {
        transitionFromPending(ReservationStatus.COMMITTED);
    }

    public void markReleased() {
        transitionFromPending(ReservationStatus.RELEASED);
    }

    public void markExpired() {
        transitionFromPending(ReservationStatus.EXPIRED);
    }

    public boolean isExpired(Instant now) {
        return status.holdsStock() && expiresAt.isBefore(now);
    }

    public boolean holdsStock() {
        return status.holdsStock();
    }

    /**
     * Settling is idempotent-by-refusal: a reservation can only leave PENDING once, so a
     * retried compensation cannot double-release stock.
     */
    private void transitionFromPending(ReservationStatus target) {
        if (status != ReservationStatus.PENDING) {
            throw new IllegalStateException(
                    "Reservation %d is already %s and cannot become %s".formatted(getId(), status, target));
        }
        this.status = target;
        this.settledAt = Instant.now();
    }
}
