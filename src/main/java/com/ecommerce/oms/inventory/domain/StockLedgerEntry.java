package com.ecommerce.oms.inventory.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only audit of physical stock movement. Nothing ever updates or deletes a row here.
 *
 * <p>The current {@code inventory_items} row tells you what stock exists; this table tells
 * you how it got that way. Recording {@code onHandAfter} and {@code reservedAfter} alongside
 * the delta means a discrepancy can be bisected to the exact movement that introduced it,
 * without replaying every row from the beginning.
 */
@Entity
@Getter
@Table(name = "stock_ledger_entries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockLedgerEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 40)
    private StockMovementType movementType;

    /** Signed: negative for stock leaving, positive for stock arriving. */
    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @Column(name = "on_hand_after", nullable = false)
    private int onHandAfter;

    @Column(name = "reserved_after", nullable = false)
    private int reservedAfter;

    /** Order number, return id, or whatever caused the movement. */
    @Column(name = "reference", length = 60)
    private String reference;

    @Column(name = "note", length = 255)
    private String note;

    /**
     * Snapshots the item's state <em>after</em> the mutation, so the caller must apply the
     * change before recording it.
     */
    public static StockLedgerEntry record(InventoryItem item, StockMovementType type,
                                          int quantityDelta, String reference, String note) {
        StockLedgerEntry entry = new StockLedgerEntry();
        entry.inventoryItem = item;
        entry.movementType = type;
        entry.quantityDelta = quantityDelta;
        entry.onHandAfter = item.getOnHand();
        entry.reservedAfter = item.getReserved();
        entry.reference = reference;
        entry.note = note;
        return entry;
    }
}
