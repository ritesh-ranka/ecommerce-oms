package com.ecommerce.oms.pricing.engine;

import com.ecommerce.oms.common.config.OmsProperties;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.error.ErrorCode;
import com.ecommerce.oms.pricing.discount.domain.Discount;
import com.ecommerce.oms.pricing.discount.domain.DiscountType;
import com.ecommerce.oms.pricing.discount.rule.DiscountRuleRegistry;
import com.ecommerce.oms.pricing.discount.rule.FlatAmountOffRule;
import com.ecommerce.oms.pricing.discount.rule.PercentageOffRule;
import com.ecommerce.oms.pricing.discount.service.DiscountService;
import com.ecommerce.oms.pricing.engine.stage.*;
import com.ecommerce.oms.pricing.tax.service.TaxCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the pricing pipeline, assembled by hand with no Spring context.
 *
 * <p>The assertions that matter most are the ones about <em>stage ordering</em> and
 * <em>apportionment</em>. Those are the two properties that no single-stage test can establish, and
 * both are financial defects if wrong: taxing a pre-discount base overcharges every discounted
 * order, and an apportionment that does not sum exactly makes correct partial refunds impossible.
 */
class PricingEngineTest {

    private static final String INR = "INR";
    private static final Long APPAREL = 4L;
    private static final Long ELECTRONICS = 1L;
    private static final Long CUSTOMER_ID = 2L;

    private static final BigDecimal APPAREL_RATE = new BigDecimal("0.05");
    private static final BigDecimal ELECTRONICS_RATE = new BigDecimal("0.18");

    private DiscountService discountService;
    private PricingEngine engine;

    @BeforeEach
    void setUp() {
        discountService = mock(DiscountService.class);

        TaxCalculator taxCalculator = mock(TaxCalculator.class);
        when(taxCalculator.resolver()).thenReturn(categoryId ->
                ELECTRONICS.equals(categoryId) ? ELECTRONICS_RATE : APPAREL_RATE);

        DiscountRuleRegistry ruleRegistry =
                new DiscountRuleRegistry(List.of(new PercentageOffRule(), new FlatAmountOffRule()));

        engine = new PricingEngine(
                List.of(
                        new TotalsStage(),                 // deliberately out of order here:
                        new TaxStage(taxCalculator),       // the engine must sort them itself
                        new LineSubtotalStage(),
                        new ShippingStage(properties()),
                        new DiscountStage(discountService, ruleRegistry)),
                properties());
    }

    private OmsProperties properties() {
        return new OmsProperties(
                new OmsProperties.Security(new OmsProperties.Security.Jwt(
                        "0123456789012345678901234567890123456789", "test", Duration.ofHours(1))),
                new OmsProperties.Inventory(Duration.ofMinutes(15), 60_000, "SINGLE_WAREHOUSE_FIRST", 3),
                new OmsProperties.Pricing(INR, ELECTRONICS_RATE,
                        new BigDecimal("49.00"), new BigDecimal("999.00")),
                new OmsProperties.Returns(7),
                new OmsProperties.Outbox(30_000, 5, Duration.ofMinutes(1)),
                new OmsProperties.Idempotency(Duration.ofHours(24)));
    }

    private PricingLine line(String sku, Long categoryId, String unitPrice, int quantity) {
        return PricingLine.ofSnapshot(1L, sku, "Product " + sku, "Default", categoryId,
                quantity, Money.of(unitPrice));
    }

    private Discount percentageOff(String code, String percent, String cap, String minOrder, Long categoryId) {
        return Discount.create(code, code + " description", DiscountType.PERCENTAGE_OFF,
                new BigDecimal(percent),
                cap == null ? null : Money.of(cap),
                minOrder == null ? null : Money.of(minOrder),
                categoryId,
                Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(30)),
                null, null);
    }

    // =================================================================================

    @Nested
    @DisplayName("without a coupon")
    class NoCoupon {

        @Test
        @DisplayName("charges tax per line at the line's own category rate")
        void taxesPerCategory() {
            PricingResult result = engine.price(List.of(
                    line("TEE-BLK-M", APPAREL, "699.00", 2),        // 1398.00 @ 5%  = 69.90
                    line("AUR-X5-128", ELECTRONICS, "1000.00", 1)), // 1000.00 @ 18% = 180.00
                    null, "WEST", CUSTOMER_ID);

            assertThat(result.subtotal()).isEqualTo(Money.of("2398.00"));
            assertThat(result.taxTotal()).isEqualTo(Money.of("249.90"));
            assertThat(result.discountTotal()).isEqualTo(Money.zero());

            Map<String, Money> taxBySku = result.lines().stream().collect(
                    java.util.stream.Collectors.toMap(
                            PricingResult.LineBreakdown::sku, PricingResult.LineBreakdown::lineTax));
            assertThat(taxBySku).containsEntry("TEE-BLK-M", Money.of("69.90"))
                    .containsEntry("AUR-X5-128", Money.of("180.00"));
        }

        @Test
        @DisplayName("waives shipping above the threshold and charges the flat fee below it")
        void appliesShippingThreshold() {
            PricingResult cheap = engine.price(List.of(line("TEE-BLK-M", APPAREL, "699.00", 1)),
                    null, "WEST", CUSTOMER_ID);
            assertThat(cheap.shippingFee()).isEqualTo(Money.of("49.00"));
            // 699.00 + 34.95 tax + 49.00 shipping
            assertThat(cheap.grandTotal()).isEqualTo(Money.of("782.95"));

            PricingResult expensive = engine.price(List.of(line("TEE-BLK-M", APPAREL, "699.00", 2)),
                    null, "WEST", CUSTOMER_ID);
            assertThat(expensive.shippingFee()).isEqualTo(Money.zero());
        }

        @Test
        @DisplayName("rejects an empty basket rather than pricing nothing")
        void rejectsEmptyBasket() {
            assertThatThrownBy(() -> engine.price(List.of(), null, "WEST", CUSTOMER_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("stage ordering")
    class StageOrdering {

        @Test
        @DisplayName("computes tax on the POST-discount base, not the list price")
        void taxFollowsDiscount() {
            Discount tenPercent = percentageOff("SAVE10", "10", null, null, null);
            when(discountService.validateForCustomer(any(), any())).thenReturn(tenPercent);

            // 1000.00 subtotal, 10% off = 100.00, taxable base 900.00, 18% tax = 162.00
            PricingResult result = engine.price(
                    List.of(line("AUR-X5-128", ELECTRONICS, "1000.00", 1)),
                    "SAVE10", "WEST", CUSTOMER_ID);

            assertThat(result.discountTotal()).isEqualTo(Money.of("100.00"));
            assertThat(result.taxTotal())
                    .as("tax must be 18%% of 900.00, not of 1000.00")
                    .isEqualTo(Money.of("162.00"));
            // 900.00 is below the 999.00 free-shipping threshold, so the fee applies:
            // 900.00 + 162.00 tax + 49.00 shipping.
            assertThat(result.shippingFee()).isEqualTo(Money.of("49.00"));
            assertThat(result.grandTotal()).isEqualTo(Money.of("1111.00"));
        }

        @Test
        @DisplayName("tests the free-shipping threshold against the post-discount subtotal")
        void shippingFollowsDiscount() {
            // 1050.00 clears the 999.00 threshold, but a 10% coupon drops it to 945.00.
            Discount tenPercent = percentageOff("SAVE10", "10", null, null, null);
            when(discountService.validateForCustomer(any(), any())).thenReturn(tenPercent);

            PricingResult result = engine.price(
                    List.of(line("TEE-BLK-M", APPAREL, "1050.00", 1)), "SAVE10", "WEST", CUSTOMER_ID);

            assertThat(result.shippingFee())
                    .as("a coupon must not buy free shipping the customer did not pay for")
                    .isEqualTo(Money.of("49.00"));
        }

        @Test
        @DisplayName("sorts stages by order() regardless of injection order")
        void engineSortsStages() {
            // setUp() supplies stages deliberately shuffled; a correct result proves sorting happened.
            PricingResult result = engine.price(
                    List.of(line("TEE-BLK-M", APPAREL, "100.00", 1)), null, "WEST", CUSTOMER_ID);

            assertThat(result.subtotal()).isEqualTo(Money.of("100.00"));
            assertThat(result.taxTotal()).isEqualTo(Money.of("5.00"));
            assertThat(result.grandTotal()).isEqualTo(Money.of("154.00")); // + 49.00 shipping
        }
    }

    @Nested
    @DisplayName("discount apportionment")
    class Apportionment {

        @Test
        @DisplayName("apportioned line shares sum EXACTLY to the discount granted, even when uneven")
        void apportionmentSumsExactly() {
            // Three lines of 33.33% each: naive rounding would lose a paisa.
            Discount tenPercent = percentageOff("SAVE10", "10", null, null, null);
            when(discountService.validateForCustomer(any(), any())).thenReturn(tenPercent);

            PricingResult result = engine.price(List.of(
                    line("A", APPAREL, "333.33", 1),
                    line("B", APPAREL, "333.33", 1),
                    line("C", APPAREL, "333.34", 1)),
                    "SAVE10", "WEST", CUSTOMER_ID);

            Money summedShares = result.lines().stream()
                    .map(PricingResult.LineBreakdown::lineDiscount)
                    .reduce(Money.zero(), Money::plus);

            assertThat(summedShares).isEqualTo(result.discountTotal());
        }

        @Test
        @DisplayName("order total always equals the sum of line totals plus shipping")
        void totalsReconcile() {
            Discount tenPercent = percentageOff("SAVE10", "10", null, null, null);
            when(discountService.validateForCustomer(any(), any())).thenReturn(tenPercent);

            PricingResult result = engine.price(List.of(
                    line("A", APPAREL, "699.00", 3),
                    line("B", ELECTRONICS, "7999.00", 1),
                    line("C", APPAREL, "1299.00", 2)),
                    "SAVE10", "WEST", CUSTOMER_ID);

            Money sumOfLines = result.lines().stream()
                    .map(PricingResult.LineBreakdown::lineTotal)
                    .reduce(Money.zero(), Money::plus)
                    .plus(result.shippingFee());

            assertThat(sumOfLines).isEqualTo(result.grandTotal());
        }

        @Test
        @DisplayName("a category-scoped coupon only discounts lines inside that scope")
        void respectsCategoryScope() {
            Discount audioOnly = percentageOff("AUDIO15", "15", null, null, ELECTRONICS);
            when(discountService.validateForCustomer(any(), any())).thenReturn(audioOnly);
            when(discountService.eligibleCategoryIds(any())).thenReturn(Set.of(ELECTRONICS));

            PricingResult result = engine.price(List.of(
                    line("TEE-BLK-M", APPAREL, "1000.00", 1),
                    line("NIM-BUDS", ELECTRONICS, "2000.00", 1)),
                    "AUDIO15", "WEST", CUSTOMER_ID);

            assertThat(result.discountTotal()).isEqualTo(Money.of("300.00")); // 15% of 2000 only

            Map<String, Money> discountBySku = result.lines().stream().collect(
                    java.util.stream.Collectors.toMap(
                            PricingResult.LineBreakdown::sku, PricingResult.LineBreakdown::lineDiscount));
            assertThat(discountBySku).containsEntry("TEE-BLK-M", Money.zero())
                    .containsEntry("NIM-BUDS", Money.of("300.00"));
        }
    }

    @Nested
    @DisplayName("coupon rejection")
    class CouponRejection {

        @Test
        @DisplayName("honours the percentage cap")
        void appliesCap() {
            Discount capped = percentageOff("SAVE10", "10", "500.00", null, null);
            when(discountService.validateForCustomer(any(), any())).thenReturn(capped);

            PricingResult result = engine.price(
                    List.of(line("VTX-LT14", ELECTRONICS, "84999.00", 1)), "SAVE10", "WEST", CUSTOMER_ID);

            assertThat(result.discountTotal())
                    .as("10%% of 84999 is 8499.90 but the cap is 500.00")
                    .isEqualTo(Money.of("500.00"));
        }

        @Test
        @DisplayName("fails with 422 when the basket is below the coupon's minimum order value")
        void enforcesMinimumOrderValue() {
            Discount needs999 = percentageOff("SAVE10", "10", null, "999.00", null);
            when(discountService.validateForCustomer(any(), any())).thenReturn(needs999);

            assertThatThrownBy(() -> engine.price(
                    List.of(line("TEE-BLK-M", APPAREL, "699.00", 1)), "SAVE10", "WEST", CUSTOMER_ID))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.DISCOUNT_NOT_APPLICABLE))
                    .hasMessageContaining("minimum order value");
        }

        @Test
        @DisplayName("fails with 422 when a scoped coupon matches nothing in the basket")
        void rejectsCouponMatchingNothing() {
            Discount audioOnly = percentageOff("AUDIO15", "15", null, null, ELECTRONICS);
            when(discountService.validateForCustomer(any(), any())).thenReturn(audioOnly);
            when(discountService.eligibleCategoryIds(any())).thenReturn(Set.of(ELECTRONICS));

            assertThatThrownBy(() -> engine.price(
                    List.of(line("TEE-BLK-M", APPAREL, "1000.00", 1)), "AUDIO15", "WEST", CUSTOMER_ID))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("does not apply to any item");
        }

        @Test
        @DisplayName("clamps a flat discount larger than the basket so the total never goes negative")
        void clampsOversizedFlatDiscount() {
            Discount flat500 = Discount.create("FLAT500", "Rs.500 off", DiscountType.FLAT_AMOUNT_OFF,
                    new BigDecimal("500"), null, null, null,
                    Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)),
                    null, null);
            when(discountService.validateForCustomer(any(), any())).thenReturn(flat500);

            PricingResult result = engine.price(
                    List.of(line("TEE-BLK-M", APPAREL, "150.00", 1)), "FLAT500", "WEST", CUSTOMER_ID);

            assertThat(result.discountTotal()).isEqualTo(Money.of("150.00"));
            assertThat(result.taxTotal()).isEqualTo(Money.zero());
            assertThat(result.grandTotal()).isEqualTo(Money.of("49.00")); // shipping only
            assertThat(result.grandTotal().isNegative()).isFalse();
        }
    }
}
