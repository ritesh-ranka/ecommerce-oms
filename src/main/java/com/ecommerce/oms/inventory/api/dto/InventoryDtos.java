package com.ecommerce.oms.inventory.api.dto;

import com.ecommerce.oms.inventory.domain.InventoryItem;
import com.ecommerce.oms.inventory.domain.StockLedgerEntry;
import com.ecommerce.oms.inventory.domain.StockMovementType;
import com.ecommerce.oms.warehouse.domain.Warehouse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    // ------------------------------------------------------------------ requests

    @Schema(name = "StockLevelRequest", description = "Absolute stock level for one (variant, warehouse) pair")
    public record StockLevelRequest(
            @NotNull @Schema(example = "9") Long variantId,
            @NotNull @Schema(example = "1") Long warehouseId,
            @NotNull @PositiveOrZero
            @Schema(description = "Units physically present. Cannot be set below units already reserved.",
                    example = "25") Integer onHand,
            @PositiveOrZero @Schema(description = "Low-stock threshold, advisory only", example = "5")
            Integer reorderLevel,
            @Size(max = 60) @Schema(description = "Audit reference, e.g. a stock-take id", example = "STOCKTAKE-2026-09")
            String reference
    ) {
    }

    @Schema(name = "StockAdjustmentRequest", description = "Relative correction: positive for inbound, negative for shrinkage")
    public record StockAdjustmentRequest(
            @NotNull @Schema(example = "9") Long variantId,
            @NotNull @Schema(example = "1") Long warehouseId,
            @NotNull @Schema(description = "Signed delta; must not push on_hand below reserved", example = "12")
            Integer quantityDelta,
            @Size(max = 60) @Schema(example = "GRN-4471") String reference,
            @Size(max = 255) @Schema(example = "Supplier delivery received") String note
    ) {
    }

    // ------------------------------------------------------------------ responses

    @Schema(name = "WarehouseStock")
    public record WarehouseStock(
            Long inventoryItemId,
            Long warehouseId,
            String warehouseCode,
            String warehouseZone,
            int onHand,
            int reserved,
            @Schema(description = "Derived as onHand - reserved; never stored") int available,
            boolean belowReorderLevel
    ) {
        public static WarehouseStock from(InventoryItem item, Warehouse warehouse) {
            return new WarehouseStock(item.getId(), warehouse.getId(), warehouse.getCode(),
                    warehouse.getZone(), item.getOnHand(), item.getReserved(), item.available(),
                    item.isBelowReorderLevel());
        }
    }

    @Schema(name = "VariantAvailability", description = "Stock for one SKU across all warehouses")
    public record VariantAvailability(
            Long variantId,
            String sku,
            String displayName,
            int totalOnHand,
            int totalReserved,
            int totalAvailable,
            List<WarehouseStock> warehouses
    ) {
    }

    @Schema(name = "StockRow")
    public record StockRow(
            Long inventoryItemId,
            Long variantId,
            String sku,
            String productName,
            Long warehouseId,
            String warehouseCode,
            int onHand,
            int reserved,
            int available,
            int reorderLevel,
            boolean belowReorderLevel
    ) {
        public static StockRow from(InventoryItem item) {
            return new StockRow(
                    item.getId(),
                    item.getVariant().getId(),
                    item.getVariant().getSku(),
                    item.getVariant().getProduct().getName(),
                    item.getWarehouse().getId(),
                    item.getWarehouse().getCode(),
                    item.getOnHand(),
                    item.getReserved(),
                    item.available(),
                    item.getReorderLevel(),
                    item.isBelowReorderLevel());
        }
    }

    @Schema(name = "LedgerRow", description = "One row of the append-only stock movement ledger")
    public record LedgerRow(
            Long id,
            StockMovementType movementType,
            int quantityDelta,
            int onHandAfter,
            int reservedAfter,
            String reference,
            String note,
            Instant occurredAt
    ) {
        public static LedgerRow from(StockLedgerEntry entry) {
            return new LedgerRow(entry.getId(), entry.getMovementType(), entry.getQuantityDelta(),
                    entry.getOnHandAfter(), entry.getReservedAfter(), entry.getReference(),
                    entry.getNote(), entry.getCreatedAt());
        }
    }
}
