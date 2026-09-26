package com.ecommerce.oms.warehouse.api;

import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.warehouse.api.dto.WarehouseDtos.*;
import com.ecommerce.oms.warehouse.service.WarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "9. Admin — Warehouses", description = "Warehouse and staff assignment management")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class WarehouseController {

    private final WarehouseService warehouseService;

    @GetMapping("/warehouses")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "List warehouses",
            description = "Readable by staff so they can identify their own location; writable only by admins.")
    public ResponseEntity<ApiResponse<List<WarehouseResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(warehouseService.findAll()));
    }

    @PostMapping("/admin/warehouses")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a warehouse")
    public ResponseEntity<ApiResponse<WarehouseResponse>> create(
            @Valid @RequestBody WarehouseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(warehouseService.create(request), "Warehouse created"));
    }

    @PutMapping("/admin/warehouses/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a warehouse or deactivate it",
            description = "Deactivating excludes it from allocation but preserves inventory and shipment history.")
    public ResponseEntity<ApiResponse<WarehouseResponse>> update(
            @PathVariable Long id, @Valid @RequestBody WarehouseRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(warehouseService.update(id, request), "Warehouse updated"));
    }

    @PostMapping("/admin/warehouses/{id}/staff")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Assign a staff member to a warehouse",
            description = """
                    The assignment scopes the fulfillment queue: staff only ever see and act on
                    shipments in warehouses they are assigned to.
                    """)
    public ResponseEntity<ApiResponse<Void>> assignStaff(
            @PathVariable Long id, @Valid @RequestBody StaffAssignmentRequest request) {
        warehouseService.assignStaff(id, request.userId());
        return ResponseEntity.ok(ApiResponse.message("Staff assigned to warehouse"));
    }
}
