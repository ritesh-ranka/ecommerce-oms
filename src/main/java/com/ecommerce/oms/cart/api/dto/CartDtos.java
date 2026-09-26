package com.ecommerce.oms.cart.api.dto;

import com.ecommerce.oms.cart.domain.Cart;
import com.ecommerce.oms.cart.domain.CartItem;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.engine.PricingResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class CartDtos {

    private CartDtos() {
    }

    // ------------------------------------------------------------------ requests

    @Schema(name = "AddCartItemRequest")
    public record AddCartItemRequest(
            @NotNull @Schema(description = "Variant (SKU) id from GET /products/{id}", example = "9")
            Long variantId,
            @NotNull @Min(1) @Max(CartItem.MAX_QUANTITY_PER_LINE)
            @Schema(description = "Units to add; merges with an existing line for the same SKU", example = "2")
            Integer quantity
    ) {
    }

    @Schema(name = "UpdateCartItemRequest")
    public record UpdateCartItemRequest(
            @NotNull @Min(1) @Max(CartItem.MAX_QUANTITY_PER_LINE)
            @Schema(description = "Absolute quantity, not a delta", example = "3")
            Integer quantity
    ) {
    }

    @Schema(name = "CartPreviewRequest")
    public record CartPreviewRequest(
            @Size(max = 40) @Schema(description = "Coupon to evaluate; an invalid one returns 422", example = "SAVE10")
            String couponCode,
            @Size(max = 40) @Schema(description = "Shipping region, affects nothing but is accepted for symmetry with checkout",
                    example = "WEST")
            String destinationZone
    ) {
    }

    // ------------------------------------------------------------------ responses

    @Schema(name = "CartItemResponse")
    public record CartItemResponse(
            Long variantId,
            String sku,
            String productName,
            String variantName,
            int quantity,
            @Schema(description = "Live catalog price; the cart stores no prices") Money unitPrice,
            @Schema(description = "unitPrice x quantity, before discount and tax") Money lineSubtotal
    ) {
        static CartItemResponse from(CartItem item) {
            return new CartItemResponse(
                    item.getVariant().getId(),
                    item.getVariant().getSku(),
                    item.getVariant().getProduct().getName(),
                    item.getVariant().getVariantName(),
                    item.getQuantity(),
                    item.getVariant().getPrice(),
                    item.getVariant().getPrice().multiply(item.getQuantity()));
        }
    }

    @Schema(name = "CartResponse", description = "Basket contents. Indicative subtotal only — call /cart/preview for the real breakdown.")
    public record CartResponse(
            Long cartId,
            int totalUnits,
            int distinctSkuCount,
            Money indicativeSubtotal,
            List<CartItemResponse> items
    ) {
        public static CartResponse from(Cart cart) {
            List<CartItemResponse> items = cart.getItems().stream()
                    .map(CartItemResponse::from)
                    .toList();
            Money subtotal = items.stream()
                    .map(CartItemResponse::lineSubtotal)
                    .reduce(Money.zero(), Money::plus);
            return new CartResponse(cart.getId(), cart.totalUnits(), cart.distinctSkuCount(), subtotal, items);
        }
    }

    @Schema(name = "CartPreviewResponse",
            description = "Full price breakdown from the same engine checkout uses, so the preview cannot disagree with the charge")
    public record CartPreviewResponse(
            int totalUnits,
            int distinctSkuCount,
            PricingResult pricing
    ) {
    }
}
