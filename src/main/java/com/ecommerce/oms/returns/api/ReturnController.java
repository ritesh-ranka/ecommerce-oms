package com.ecommerce.oms.returns.api;

import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.returns.api.dto.ReturnDtos.*;
import com.ecommerce.oms.returns.service.ReturnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Returns and refunds.
 *
 * <p>Split across two authorization levels on purpose: a customer may <em>ask</em>, but only staff or an
 * admin may approve. Letting the requester also approve would mean self-service refunds with no
 * inspection of the goods.
 */
@Tag(name = "7. Returns & Refunds", description = "Request returns (CUSTOMER), approve or reject (STAFF, ADMIN)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ReturnController {

    private final ReturnService returnService;

    // ------------------------------------------------------------------ customer

    @PostMapping("/orders/{orderId}/returns")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Request a full or partial return",
            description = """
                    Permitted only on a `DELIVERED` order, within the configured return window
                    (`oms.returns.window-days`, default 7) measured **from delivery** — a parcel that took
                    two weeks to arrive should not eat the customer's return period.

                    Partial returns are supported per line, and repeatedly: return 2 of 5 shirts today and
                    2 more next week. Quantities are validated against what is still returnable, counting
                    both approved and still-pending requests, so two pending requests cannot together
                    return more units than were bought.

                    The refund shown is what those units **actually cost** — their share of the order
                    discount and their own tax are both reversed. Nothing is refunded or restocked until
                    staff approve.
                    """)
    public ResponseEntity<ApiResponse<ReturnResponse>> requestReturn(
            @AuthenticationPrincipal OmsUserPrincipal customer,
            @PathVariable Long orderId,
            @Valid @RequestBody CreateReturnRequest request) {
        ReturnResponse response = returnService.request(orderId, customer, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Return requested and awaiting review"));
    }

    @GetMapping("/orders/{orderId}/returns")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Return history for one order")
    public ResponseEntity<ApiResponse<List<ReturnResponse>>> forOrder(
            @AuthenticationPrincipal OmsUserPrincipal caller,
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(returnService.forOrder(orderId, caller)));
    }

    @GetMapping("/returns/{returnId}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Get one return request",
            description = "Ownership is checked through the parent order, so a customer cannot read another's return.")
    public ResponseEntity<ApiResponse<ReturnResponse>> findById(
            @AuthenticationPrincipal OmsUserPrincipal caller,
            @PathVariable Long returnId) {
        return ResponseEntity.ok(ApiResponse.ok(returnService.findById(returnId, caller)));
    }

    // ------------------------------------------------------------------ staff / admin

    @GetMapping("/returns/pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Returns awaiting a decision", description = "The staff review queue, oldest first.")
    public ResponseEntity<ApiResponse<PageResponse<ReturnResponse>>> pending(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(returnService.pendingQueue(pageable)));
    }

    @PostMapping("/returns/{returnId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Approve a return: refund and restock",
            description = """
                    Runs in one transaction, **refund first**: if the gateway refuses, everything rolls back
                    and the request stays `REQUESTED` for a retry. Restocking first and then failing to
                    refund would leave the customer out of pocket with their goods already resold.

                    Goods are restocked to the warehouse that actually shipped them, which keeps the
                    physical shelf and the allocation strategy's proximity data in agreement.

                    `DAMAGED` and `DEFECTIVE` returns are refunded but deliberately **not** restocked —
                    putting them back would promise a unit that cannot be shipped.

                    The order becomes `RETURNED` if every unit has now come back, otherwise it returns to
                    `DELIVERED` so the remaining items stay returnable.
                    """)
    public ResponseEntity<ApiResponse<ReturnResponse>> approve(
            @AuthenticationPrincipal OmsUserPrincipal staff,
            @PathVariable Long returnId,
            @RequestBody(required = false) @Valid ResolveReturnRequest request) {
        ReturnResponse response = returnService.approve(
                returnId, staff, request == null ? null : request.note());
        return ResponseEntity.ok(ApiResponse.ok(response, "Return approved, refunded, and restocked"));
    }

    @PostMapping("/returns/{returnId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Reject a return",
            description = """
                    Nothing is refunded or restocked. The order goes back to `DELIVERED` so the customer can
                    raise a fresh request rather than being permanently stuck.
                    """)
    public ResponseEntity<ApiResponse<ReturnResponse>> reject(
            @AuthenticationPrincipal OmsUserPrincipal staff,
            @PathVariable Long returnId,
            @RequestBody(required = false) @Valid ResolveReturnRequest request) {
        ReturnResponse response = returnService.reject(
                returnId, staff, request == null ? null : request.note());
        return ResponseEntity.ok(ApiResponse.ok(response, "Return rejected"));
    }
}
