package com.ecommerce.oms.inventory.service;

import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.catalog.service.ProductService;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.inventory.api.dto.InventoryDtos.*;
import com.ecommerce.oms.inventory.domain.InventoryItem;
import com.ecommerce.oms.inventory.domain.StockLedgerEntry;
import com.ecommerce.oms.inventory.domain.StockMovementType;
import com.ecommerce.oms.inventory.repository.InventoryItemRepository;
import com.ecommerce.oms.inventory.repository.StockLedgerEntryRepository;
import com.ecommerce.oms.warehouse.domain.Warehouse;
import com.ecommerce.oms.warehouse.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stock administration and availability reads.
 *
 * <p>Split from {@link ReservationService} on purpose. This class serves humans: an admin
 * correcting a stock-take, a staff member checking what is on the shelf. That service serves
 * the checkout path and is the one place concurrency correctness is argued. Keeping them apart
 * means an admin convenience method can never quietly become a third way to mutate reserved
 * stock.
 *
 * <p>Writes here still take the same pessimistic lock and still write the same ledger, so an
 * adjustment concurrent with a checkout is serialised rather than lost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryItemRepository inventoryItemRepository;
    private final StockLedgerEntryRepository ledgerRepository;
    private final ProductService productService;
    private final WarehouseService warehouseService;

    // ------------------------------------------------------------------ reads

    /**
     * Availability of one SKU across every warehouse, plus the network total.
     *
     * <p>This is the endpoint that makes the oversell demo legible: run it before and after the
     * concurrency test and the numbers reconcile.
     */
    @Transactional(readOnly = true)
    public VariantAvailability availabilityOf(Long variantId) {
        ProductVariant variant = productService.loadPurchasableVariant(variantId);
        List<InventoryItem> items = inventoryItemRepository.findByVariantId(variantId);

        List<WarehouseStock> perWarehouse = items.stream()
                .map(item -> WarehouseStock.from(item, item.getWarehouse()))
                .toList();

        int totalOnHand = perWarehouse.stream().mapToInt(WarehouseStock::onHand).sum();
        int totalReserved = perWarehouse.stream().mapToInt(WarehouseStock::reserved).sum();

        log.debug("Availability for variantId={} sku={}: onHand={} reserved={} available={}",
                variantId, variant.getSku(), totalOnHand, totalReserved, totalOnHand - totalReserved);

        return new VariantAvailability(variantId, variant.getSku(), variant.displayName(),
                totalOnHand, totalReserved, totalOnHand - totalReserved, perWarehouse);
    }

    @Transactional(readOnly = true)
    public List<StockRow> search(Long variantId, Long warehouseId) {
        return inventoryItemRepository.search(variantId, warehouseId).stream()
                .map(StockRow::from)
                .toList();
    }

    /** Operational report: everything at or below its reorder threshold. */
    @Transactional(readOnly = true)
    public List<StockRow> lowStock() {
        List<StockRow> rows = inventoryItemRepository.findLowStock().stream()
                .map(StockRow::from)
                .toList();
        log.debug("Low-stock report returned {} row(s)", rows.size());
        return rows;
    }

    @Transactional(readOnly = true)
    public List<LedgerRow> ledgerFor(Long inventoryItemId, org.springframework.data.domain.Pageable pageable) {
        return ledgerRepository.findByInventoryItemIdOrderByIdDesc(inventoryItemId, pageable)
                .map(LedgerRow::from)
                .getContent();
    }

    // ------------------------------------------------------------------ writes

    /**
     * Creates or replaces the stock level for one (variant, warehouse) pair.
     *
     * <p>Absolute set, not a delta, because that is what a physical stock-take produces. The
     * domain refuses to set {@code onHand} below what is already reserved: those units belong to
     * customers with confirmed orders, and silently invalidating them would trade a visible
     * error for an invisible one.
     */
    @Transactional
    public StockRow setStockLevel(StockLevelRequest request) {
        ProductVariant variant = productService.loadPurchasableVariant(request.variantId());
        Warehouse warehouse = warehouseService.load(request.warehouseId());

        InventoryItem item = inventoryItemRepository
                .findByVariantIdAndWarehouseId(variant.getId(), warehouse.getId())
                .map(existing -> lockFresh(existing.getId()))
                .orElseGet(() -> inventoryItemRepository.save(InventoryItem.create(
                        variant, warehouse, 0,
                        request.reorderLevel() == null ? 0 : request.reorderLevel())));

        int previousOnHand = item.getOnHand();
        int delta = request.onHand() - previousOnHand;

        try {
            item.setOnHand(request.onHand());
        } catch (IllegalStateException blocked) {
            throw ApiException.businessRule(blocked.getMessage());
        }
        if (request.reorderLevel() != null) {
            item.setReorderLevel(request.reorderLevel());
        }

        inventoryItemRepository.save(item);
        if (delta != 0) {
            ledgerRepository.save(StockLedgerEntry.record(item,
                    delta > 0 ? StockMovementType.INBOUND : StockMovementType.ADJUSTMENT,
                    delta, request.reference(),
                    "Stock level set to %d (was %d)".formatted(request.onHand(), previousOnHand)));
        }

        log.info("Stock set: sku={} warehouse={} onHand {} -> {} (reserved={})",
                variant.getSku(), warehouse.getCode(), previousOnHand, item.getOnHand(), item.getReserved());
        return StockRow.from(item);
    }

    /**
     * Relative correction — goods received, shrinkage, damage.
     *
     * <p>Kept separate from {@link #setStockLevel} because a delta is safe to apply concurrently
     * with another adjustment while an absolute set is not: two people each setting the level
     * from a stale reading would lose one of the two changes.
     */
    @Transactional
    public StockRow adjustStock(StockAdjustmentRequest request) {
        InventoryItem item = inventoryItemRepository
                .findByVariantIdAndWarehouseId(request.variantId(), request.warehouseId())
                .map(existing -> lockFresh(existing.getId()))
                .orElseThrow(() -> ApiException.notFound(
                        "No stock row for variant %d at warehouse %d — set an initial level first"
                                .formatted(request.variantId(), request.warehouseId())));

        int before = item.getOnHand();
        if (request.quantityDelta() > 0) {
            item.addOnHand(request.quantityDelta());
        } else if (request.quantityDelta() < 0) {
            try {
                item.setOnHand(before + request.quantityDelta());
            } catch (IllegalStateException | IllegalArgumentException blocked) {
                throw ApiException.businessRule(blocked.getMessage());
            }
        }

        inventoryItemRepository.save(item);
        ledgerRepository.save(StockLedgerEntry.record(item, StockMovementType.ADJUSTMENT,
                request.quantityDelta(), request.reference(), request.note()));

        log.info("Stock adjusted: variantId={} warehouseId={} delta={} onHand {} -> {}",
                request.variantId(), request.warehouseId(), request.quantityDelta(), before, item.getOnHand());
        return StockRow.from(item);
    }

    /**
     * Returns goods to sellable stock. Called by the return flow, which restocks to the
     * warehouse that actually shipped the unit rather than to a default location.
     */
    @Transactional
    public void restock(Long variantId, Long warehouseId, int quantity, String reference, String note) {
        InventoryItem item = inventoryItemRepository
                .findByVariantIdAndWarehouseId(variantId, warehouseId)
                .map(existing -> lockFresh(existing.getId()))
                .orElseThrow(() -> ApiException.notFound(
                        "No stock row for variant %d at warehouse %d".formatted(variantId, warehouseId)));

        item.addOnHand(quantity);
        inventoryItemRepository.save(item);
        ledgerRepository.save(StockLedgerEntry.record(item, StockMovementType.RETURN_RESTOCK,
                quantity, reference, note));

        log.info("Restocked {} unit(s) of variantId={} to warehouseId={} (onHand now {})",
                quantity, variantId, warehouseId, item.getOnHand());
    }

    /** Re-reads the row under a pessimistic lock so admin writes serialise against checkout. */
    private InventoryItem lockFresh(Long inventoryItemId) {
        return inventoryItemRepository.lockAllByIdInOrder(List.of(inventoryItemId)).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Inventory item", inventoryItemId));
    }
}
