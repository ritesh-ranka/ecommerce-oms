package com.ecommerce.oms.order.domain;

import com.ecommerce.oms.catalog.domain.Category;
import com.ecommerce.oms.catalog.domain.Product;
import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.common.domain.Address;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.engine.PricingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for partial-refund arithmetic.
 *
 * <p>This is the subtle bit of the returns flow and the reason {@code OrderLine} persists a per-line
 * discount share and tax. Two properties are asserted:
 *
 * <ol>
 *   <li>a refund returns what the customer <em>actually paid</em> for those units — discount and tax
 *       included — not their list price;</li>
 *   <li>successive partial refunds of one line sum to <b>exactly</b> the line total, with no paisa
 *       stranded by repeated rounding.</li>
 * </ol>
 *
 * <p>Property 2 is why the final slice of a line takes the remainder rather than another computed
 * proportion. Three equal thirds of ₹100 are 33.33 each and lose a paisa; the third refund therefore has
 * to be 33.34.
 */
class OrderLineRefundMathTest {

    private static final Long VARIANT_ID = 1L;

    /**
     * Builds a real order through the production factory, so the test exercises the same construction
     * path checkout uses rather than a hand-assembled entity.
     */
    private Order orderWithOneLine(int quantity, String lineSubtotal, String lineDiscount,
                                   String lineTax, String lineTotal) {
        Category category = Category.create("Apparel", "apparel", null);
        Product product = Product.create("Everyday Cotton Tee", null, "Loom", category);
        ProductVariant variant = ProductVariant.create(
                "TEE-BLK-M", "Black / M", Money.of("699.00"), 180);
        product.addVariant(variant);

        PricingResult.LineBreakdown priced = new PricingResult.LineBreakdown(
                VARIANT_ID, "TEE-BLK-M", "Everyday Cotton Tee", "Black / M",
                quantity, Money.of("699.00"),
                Money.of(lineSubtotal), Money.of(lineDiscount), Money.of(lineTax),
                new BigDecimal("0.05"), Money.of(lineTotal));

        PricingResult pricing = new PricingResult(
                Money.of(lineSubtotal), Money.of(lineDiscount), Money.of(lineTax),
                Money.zero(), Money.of(lineTotal), null, null, List.of(priced));

        return Order.awaitingPayment("ORD-TEST-0001", 2L, pricing,
                new Address("Cara", "42 Marine Drive", "Mumbai", "WEST", "400020", "9000000002"),
                Map.of(VARIANT_ID, variant));
    }

    @Test
    @DisplayName("three successive single-unit refunds of a 100.00 line sum to exactly 100.00")
    void unevenThreeWaySplitSumsExactly() {
        Order order = orderWithOneLine(3, "100.00", "0.00", "0.00", "100.00");
        OrderLine line = order.getLines().get(0);

        Money refundedSoFar = Money.zero();

        Money first = line.refundValueFor(1, refundedSoFar);
        assertThat(first).isEqualTo(Money.of("33.33"));
        order.recordReturnedUnits(line, 1);
        refundedSoFar = refundedSoFar.plus(first);

        Money second = line.refundValueFor(1, refundedSoFar);
        assertThat(second).isEqualTo(Money.of("33.33"));
        order.recordReturnedUnits(line, 1);
        refundedSoFar = refundedSoFar.plus(second);

        // The last unit completes the line, so it absorbs the rounding remainder.
        Money third = line.refundValueFor(1, refundedSoFar);
        assertThat(third)
                .as("final slice must take the remainder, not another computed third")
                .isEqualTo(Money.of("33.34"));
        order.recordReturnedUnits(line, 1);
        refundedSoFar = refundedSoFar.plus(third);

        assertThat(refundedSoFar).isEqualTo(Money.of("100.00"));
        assertThat(line.isFullyReturned()).isTrue();
    }

    @Test
    @DisplayName("refund reverses the line's discount share and its tax, not the list price")
    void refundsWhatWasActuallyPaid() {
        // 2 units at 699.00 = 1398.00 subtotal, 139.80 discount share, 62.91 tax on 1258.20.
        Order order = orderWithOneLine(2, "1398.00", "139.80", "62.91", "1321.11");
        OrderLine line = order.getLines().get(0);

        Money oneUnit = line.refundValueFor(1, Money.zero());

        assertThat(oneUnit)
                .as("half of the line total (1321.11), not half the list price (1398.00)")
                .isEqualTo(Money.of("660.56"));   // 1321.11 / 2, HALF_UP
        assertThat(oneUnit).isLessThan(line.getUnitPrice());
    }

    @Test
    @DisplayName("returning the whole line in one go refunds the entire line total")
    void fullLineReturnRefundsEverything() {
        Order order = orderWithOneLine(5, "3495.00", "349.50", "157.28", "3302.78");
        OrderLine line = order.getLines().get(0);

        assertThat(line.refundValueFor(5, Money.zero())).isEqualTo(Money.of("3302.78"));
    }

    @Test
    @DisplayName("a mixed sequence — 2 units then the remaining 3 — still sums exactly")
    void mixedPartialSequenceSumsExactly() {
        Order order = orderWithOneLine(5, "1000.00", "0.00", "0.00", "1000.00");
        OrderLine line = order.getLines().get(0);

        Money first = line.refundValueFor(2, Money.zero());
        assertThat(first).isEqualTo(Money.of("400.00"));
        order.recordReturnedUnits(line, 2);

        Money second = line.refundValueFor(3, first);
        assertThat(second).isEqualTo(Money.of("600.00"));
        order.recordReturnedUnits(line, 3);

        assertThat(first.plus(second)).isEqualTo(Money.of("1000.00"));
    }

    @Test
    @DisplayName("returnable quantity shrinks as units come back")
    void tracksReturnableQuantity() {
        Order order = orderWithOneLine(4, "400.00", "0.00", "0.00", "400.00");
        OrderLine line = order.getLines().get(0);

        assertThat(line.returnableQuantity()).isEqualTo(4);
        order.recordReturnedUnits(line, 3);
        assertThat(line.returnableQuantity()).isEqualTo(1);
        assertThat(line.isFullyReturned()).isFalse();

        order.recordReturnedUnits(line, 1);
        assertThat(line.returnableQuantity()).isZero();
        assertThat(line.isFullyReturned()).isTrue();
    }

    @Test
    @DisplayName("refuses to return more units than were bought, so a double approval cannot over-refund")
    void refusesToOverReturn() {
        Order order = orderWithOneLine(2, "200.00", "0.00", "0.00", "200.00");
        OrderLine line = order.getLines().get(0);

        order.recordReturnedUnits(line, 2);

        assertThatThrownBy(() -> order.recordReturnedUnits(line, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only 0 of 2 remain returnable");
    }

    @Test
    @DisplayName("the order's running refund total refuses to exceed what was charged")
    void refundTotalCannotExceedCharge() {
        Order order = orderWithOneLine(1, "100.00", "0.00", "0.00", "100.00");

        order.addRefund(Money.of("100.00"));
        assertThat(order.getRefundedTotal()).isEqualTo(Money.of("100.00"));
        assertThat(order.refundableRemaining()).isEqualTo(Money.zero());

        assertThatThrownBy(() -> order.addRefund(Money.of("0.01")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeding");
    }
}
