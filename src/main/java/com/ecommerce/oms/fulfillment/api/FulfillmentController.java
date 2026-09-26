package com.ecommerce.oms.fulfillment.api;

import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.fulfillment.api.dto.FulfillmentDtos.*;
import com.ecommerce.oms.fulfillment.domain.ShipmentStatus;
import com.ecommerce.oms.fulfillment.service.FulfillmentService;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Warehouse staff operations.
 *
 * <p>The role check here is only half the rule: a staff member may call these endpoints, but the service
 * additionally enforces that the shipment belongs to a warehouse they are assigned to. Without that
 * second check, any staff account could advance any parcel in the company.
 */
@Tag(name = "7. Fulfillment", description = "Shipment lifecycle for warehouse staff (WAREHOUSE_STAFF, ADMIN)")
@RestController
@RequestMapping("/api/v1/fulfillment")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class FulfillmentController {

    private final FulfillmentService fulfillmentService;

    @GetMapping("/queue")
    @Operation(summary = "The caller's shipment work queue",
            description = """
                    Scoped to the warehouses the authenticated staff member is assigned to; admins see all.
                    Filter by status to get just the parcels needing action, e.g. `status=PENDING`.

                    Shipments appear here as a result of the **asynchronous** outbox pipeline, so a queue
                    entry is itself evidence that routing ran after the checkout response was sent.
                    """)
    public ResponseEntity<ApiResponse<PageResponse<ShipmentResponse>>> queue(
            @AuthenticationPrincipal OmsUserPrincipal staff,
            @RequestParam(required = false) ShipmentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(fulfillmentService.queueFor(staff, status, pageable)));
    }

    @GetMapping("/orders/{orderId}/shipments")
    @Operation(summary = "Every parcel for one order",
            description = "A split allocation produces more than one, each moving independently.")
    public ResponseEntity<ApiResponse<List<ShipmentResponse>>> forOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(fulfillmentService.forOrder(orderId)));
    }

    @PostMapping("/shipments/{shipmentId}/status")
    @Operation(summary = "Advance a parcel: PACKED, SHIPPED, or DELIVERED",
            description = """
                    Enforces the shipment transition table, so steps cannot be skipped and a dispatched
                    parcel cannot be cancelled (409 `ILLEGAL_TRANSITION`).

                    The **order** status is then derived from all of its parcels, using the least advanced
                    one — so a two-parcel order only becomes SHIPPED once both have shipped, rather than
                    telling the customer their whole order is on its way when half of it is still on a
                    shelf.

                    Acting on a shipment in a warehouse you are not assigned to returns 404, not 403:
                    confirming it exists would leak another warehouse's workload.
                    """)
    public ResponseEntity<ApiResponse<ShipmentResponse>> advance(
            @AuthenticationPrincipal OmsUserPrincipal staff,
            @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentStatusUpdateRequest request) {
        ShipmentResponse response = fulfillmentService.advance(shipmentId, request.status(), staff);
        return ResponseEntity.ok(ApiResponse.ok(response, "Shipment moved to " + request.status()));
    }
}
