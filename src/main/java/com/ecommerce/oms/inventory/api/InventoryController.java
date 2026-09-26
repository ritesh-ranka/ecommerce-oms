package com.ecommerce.oms.inventory.api;

import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.inventory.api.dto.InventoryDtos.*;
import com.ecommerce.oms.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Stock visibility and administration.
 *
 * <p>Reads are open to {@code ADMIN} and {@code WAREHOUSE_STAFF} — staff need to see what is on
 * the shelf to pick an order. Writes are {@code ADMIN} only: a staff member moving stock levels
 * around would be able to manufacture availability out of nothing.
 *
 * <p>Customers deliberately have no access. Exposing exact stock counts publicly is a
 * competitive-intelligence leak, and the checkout response already tells a customer everything
 * they need ("1 available, you asked for 3").
 */
@Tag(name = "4. Inventory", description = "Stock levels across warehouses (ADMIN write, STAFF read)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class InventoryController {

    private final InventoryService inventoryService;

    // ------------------------------------------------------------------ reads

    @GetMapping("/inventory")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Search stock rows by variant and/or warehouse",
            description = "Both filters are optional. Omitting both returns every stock row.")
    public ResponseEntity<ApiResponse<List<StockRow>>> search(
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) Long warehouseId) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.search(variantId, warehouseId)));
    }

    @GetMapping("/inventory/variants/{variantId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Availability of one SKU across every warehouse",
            description = """
                    Returns per-warehouse on_hand / reserved / available plus network totals.
                    `available` is always derived as (on_hand - reserved) and is never stored.

                    Useful as the before/after check when demonstrating oversell prevention:
                    variant 9 (TEE-BLK-M) is seeded with exactly one unit network-wide.
                    """)
    public ResponseEntity<ApiResponse<VariantAvailability>> availability(@PathVariable Long variantId) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.availabilityOf(variantId)));
    }

    @GetMapping("/inventory/low-stock")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Rows at or below their reorder level")
    public ResponseEntity<ApiResponse<List<StockRow>>> lowStock() {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.lowStock()));
    }

    @GetMapping("/inventory/{inventoryItemId}/ledger")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Movement history for one stock row",
            description = """
                    Append-only audit trail: every reserve, release, expiry, sale, restock, and
                    adjustment, newest first, each recording the resulting on_hand and reserved.
                    This is how a stock discrepancy is traced to the movement that caused it.
                    """)
    public ResponseEntity<ApiResponse<List<LedgerRow>>> ledger(
            @PathVariable Long inventoryItemId,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryService.ledgerFor(inventoryItemId, pageable)));
    }

    // ------------------------------------------------------------------ writes

    @PutMapping("/admin/inventory")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Set an absolute stock level (stock-take)",
            description = """
                    Creates the stock row if this SKU has never been stocked at this warehouse.

                    Refused with 422 if the new level is below the units already reserved for open
                    orders — those belong to customers who have a confirmed order.
                    """)
    public ResponseEntity<ApiResponse<StockRow>> setStockLevel(
            @Valid @RequestBody StockLevelRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(inventoryService.setStockLevel(request), "Stock level updated"));
    }

    @PostMapping("/admin/inventory/adjustments")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Apply a relative stock correction",
            description = """
                    Positive for goods received, negative for shrinkage or damage. Safe to apply
                    concurrently with another adjustment, unlike an absolute set.
                    """)
    public ResponseEntity<ApiResponse<StockRow>> adjust(
            @Valid @RequestBody StockAdjustmentRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(inventoryService.adjustStock(request), "Stock adjusted"));
    }
}
