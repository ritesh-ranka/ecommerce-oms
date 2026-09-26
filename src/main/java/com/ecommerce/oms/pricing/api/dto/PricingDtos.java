package com.ecommerce.oms.pricing.api.dto;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.discount.domain.Discount;
import com.ecommerce.oms.pricing.discount.domain.DiscountType;
import com.ecommerce.oms.pricing.tax.domain.TaxRate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public final class PricingDtos {

    private PricingDtos() {
    }

    // ------------------------------------------------------------------ discounts

    @Schema(name = "DiscountRequest")
    public record DiscountRequest(
            @NotBlank @Size(max = 40)
            @Pattern(regexp = "^[A-Z0-9_-]+$", message = "Code may contain uppercase letters, digits, underscores, and hyphens")
            @Schema(example = "WELCOME15") String code,

            @Size(max = 255) @Schema(example = "15% off your first order, capped at Rs.750") String description,

            @NotNull @Schema(example = "PERCENTAGE_OFF") DiscountType type,

            @NotNull @DecimalMin(value = "0.0001", message = "Discount value must be positive")
            @Schema(description = "Percentage for PERCENTAGE_OFF (15 = 15%), absolute amount for FLAT_AMOUNT_OFF",
                    example = "15.0") BigDecimal discountValue,

            @DecimalMin("0.00") @Schema(description = "Cap for percentage discounts; omit for uncapped",
                    example = "750.00") BigDecimal maxDiscount,

            @DecimalMin("0.00") @Schema(description = "Minimum basket subtotal, checked against the whole order",
                    example = "999.00") BigDecimal minOrderValue,

            @Schema(description = "Restrict to this category and its descendants; omit for the whole basket",
                    example = "3") Long categoryId,

            @NotNull @Schema(example = "2026-01-01T00:00:00Z") Instant startsAt,
            @NotNull @Schema(example = "2027-01-01T00:00:00Z") Instant endsAt,

            @Positive @Schema(description = "Total redemptions across all customers; omit for unlimited",
                    example = "1000") Integer usageLimit,
            @Positive @Schema(description = "Redemptions per customer; omit for unlimited", example = "1")
            Integer perCustomerLimit,

            @Schema(example = "true") Boolean active
    ) {
    }

    @Schema(name = "DiscountResponse")
    public record DiscountResponse(
            Long id,
            String code,
            String description,
            DiscountType type,
            BigDecimal discountValue,
            Money maxDiscount,
            Money minOrderValue,
            Long categoryId,
            Instant startsAt,
            Instant endsAt,
            Integer usageLimit,
            Integer perCustomerLimit,
            int timesRedeemed,
            boolean active,
            @Schema(description = "Whether the coupon itself is usable right now, ignoring basket and buyer")
            boolean currentlyRedeemable
    ) {
        public static DiscountResponse from(Discount discount) {
            return new DiscountResponse(
                    discount.getId(), discount.getCode(), discount.getDescription(), discount.getType(),
                    discount.getDiscountValue(), discount.getMaxDiscount(), discount.getMinOrderValue(),
                    discount.getCategoryId(), discount.getStartsAt(), discount.getEndsAt(),
                    discount.getUsageLimit(), discount.getPerCustomerLimit(), discount.getTimesRedeemed(),
                    discount.isActive(), discount.isRedeemableAt(Instant.now()));
        }
    }

    // ------------------------------------------------------------------ tax rates

    @Schema(name = "TaxRateRequest")
    public record TaxRateRequest(
            @NotNull @Schema(example = "2") Long categoryId,
            @NotNull @DecimalMin("0.000000") @DecimalMax("1.000000")
            @Schema(description = "Fraction, not percentage: 0.18 means 18%", example = "0.18") BigDecimal rate,
            @Size(max = 120) @Schema(example = "GST 18% — Mobiles") String description
    ) {
    }

    @Schema(name = "TaxRateResponse")
    public record TaxRateResponse(
            Long id,
            Long categoryId,
            BigDecimal rate,
            String description
    ) {
        public static TaxRateResponse from(TaxRate taxRate) {
            return new TaxRateResponse(taxRate.getId(), taxRate.getCategoryId(),
                    taxRate.getRate(), taxRate.getDescription());
        }
    }
}
