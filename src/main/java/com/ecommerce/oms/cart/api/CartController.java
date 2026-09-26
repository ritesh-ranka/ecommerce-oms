package com.ecommerce.oms.cart.api;

import com.ecommerce.oms.cart.api.dto.CartDtos.*;
import com.ecommerce.oms.cart.service.CartService;
import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Cart operations for the signed-in customer.
 *
 * <p>Every method derives the cart from the authenticated principal. No endpoint accepts a cart id
 * or a user id, so there is no parameter an attacker could tamper with to reach someone else's
 * basket — the ownership check is structural rather than a guard that could be forgotten.
 */
@Tag(name = "5. Cart", description = "Basket management and price preview (CUSTOMER)")
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
public class CartController {

    private final CartService cartService;

    @GetMapping
    @Operation(summary = "Get the current cart",
            description = "Returns an indicative subtotal only. Use /cart/preview for discounts, tax, and shipping.")
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @AuthenticationPrincipal OmsUserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(cartService.getCart(principal.getId())));
    }

    @PostMapping("/items")
    @Operation(summary = "Add units of a SKU",
            description = "Merges with an existing line for the same SKU rather than creating a duplicate.")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @AuthenticationPrincipal OmsUserPrincipal principal,
            @Valid @RequestBody AddCartItemRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(cartService.addItem(principal.getId(), request), "Item added to cart"));
    }

    @PatchMapping("/items/{variantId}")
    @Operation(summary = "Set the quantity of a line",
            description = "Absolute value, not a delta. Use DELETE to remove the line entirely.")
    public ResponseEntity<ApiResponse<CartResponse>> updateQuantity(
            @AuthenticationPrincipal OmsUserPrincipal principal,
            @PathVariable Long variantId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                cartService.updateQuantity(principal.getId(), variantId, request), "Quantity updated"));
    }

    @DeleteMapping("/items/{variantId}")
    @Operation(summary = "Remove a line from the cart")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @AuthenticationPrincipal OmsUserPrincipal principal,
            @PathVariable Long variantId) {
        return ResponseEntity.ok(ApiResponse.ok(
                cartService.removeItem(principal.getId(), variantId), "Item removed"));
    }

    @DeleteMapping
    @Operation(summary = "Empty the cart")
    public ResponseEntity<ApiResponse<Void>> clear(@AuthenticationPrincipal OmsUserPrincipal principal) {
        cartService.clear(principal.getId());
        return ResponseEntity.ok(ApiResponse.message("Cart cleared"));
    }

    @PostMapping("/preview")
    @Operation(summary = "Dry-run the full price breakdown",
            description = """
                    Runs the **same pricing engine** checkout runs, so the preview cannot disagree
                    with the amount charged. Reserves no stock and creates no order.

                    Returns per-line subtotal, apportioned discount share, tax rate and tax, plus
                    order-level discount, tax, shipping, and grand total.

                    An invalid or inapplicable coupon returns `422 DISCOUNT_NOT_APPLICABLE` with the
                    reason, rather than silently pricing without it. Try `SAVE10`, `FLAT200`,
                    `AUDIO15`, or `EXPIRED5` to see each branch.
                    """)
    public ResponseEntity<ApiResponse<CartPreviewResponse>> preview(
            @AuthenticationPrincipal OmsUserPrincipal principal,
            @RequestBody(required = false) @Valid CartPreviewRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(cartService.preview(principal.getId(), request)));
    }
}
