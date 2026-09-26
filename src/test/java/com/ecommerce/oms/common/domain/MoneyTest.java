package com.ecommerce.oms.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the money value object.
 *
 * <p>Worth testing thoroughly despite being small: every price, discount, tax, total, and refund in
 * the system flows through this class, so a rounding or equality defect here is a financial defect
 * everywhere at once.
 */
class MoneyTest {

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @ParameterizedTest(name = "{0} normalises to {1}")
        @CsvSource({
                "10.005, 10.01",   // HALF_UP, not HALF_EVEN
                "10.004, 10.00",
                "10.1,   10.10",
                "10,     10.00",
                "0.001,  0.00",
                "-1.005, -1.01"
        })
        @DisplayName("always carries scale 2 with HALF_UP rounding")
        void normalisesScaleAndRounding(String input, String expected) {
            assertThat(Money.of(input).amount()).isEqualByComparingTo(expected);
            assertThat(Money.of(input).amount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("treats amounts written differently as equal once normalised")
        void equalityIgnoresRepresentation() {
            assertThat(Money.of("10.00")).isEqualTo(Money.of("10"));
            assertThat(Money.of("10.00")).hasSameHashCodeAs(Money.of("10"));
        }

        @Test
        @DisplayName("uppercases the currency code and rejects anything that is not three letters")
        void validatesCurrency() {
            assertThat(Money.of(new BigDecimal("5"), "inr").currency()).isEqualTo("INR");
            assertThatThrownBy(() -> Money.of(new BigDecimal("5"), "RUPEE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("3-letter");
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("adds, subtracts, and multiplies without drift")
        void basicOperations() {
            assertThat(Money.of("10.50").plus(Money.of("4.50"))).isEqualTo(Money.of("15.00"));
            assertThat(Money.of("10.50").minus(Money.of("4.50"))).isEqualTo(Money.of("6.00"));
            assertThat(Money.of("699.00").multiply(3)).isEqualTo(Money.of("2097.00"));
        }

        @Test
        @DisplayName("refuses to combine different currencies instead of silently producing a wrong number")
        void rejectsMixedCurrency() {
            Money rupees = Money.of(new BigDecimal("100"), "INR");
            Money dollars = Money.of(new BigDecimal("100"), "USD");

            assertThatThrownBy(() -> rupees.plus(dollars))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("INR")
                    .hasMessageContaining("USD");
        }

        @Test
        @DisplayName("percentage() reads its argument as a percentage, rate() as a fraction")
        void percentageVersusRate() {
            // 10% of 2500.00
            assertThat(Money.of("2500.00").percentage(new BigDecimal("10"))).isEqualTo(Money.of("250.00"));
            // 18% tax expressed as 0.18
            assertThat(Money.of("2500.00").rate(new BigDecimal("0.18"))).isEqualTo(Money.of("450.00"));
        }

        @Test
        @DisplayName("atLeastZero clamps negatives, so a discount can never invert a total")
        void clampsNegatives() {
            assertThat(Money.of("100.00").minus(Money.of("150.00")).atLeastZero()).isEqualTo(Money.zero());
            assertThat(Money.of("100.00").minus(Money.of("40.00")).atLeastZero()).isEqualTo(Money.of("60.00"));
        }
    }

    @Nested
    @DisplayName("proration")
    class Proration {

        @Test
        @DisplayName("splits by count, leaving a remainder for the caller to absorb")
        void proratesByCount() {
            Money total = Money.of("100.00");

            assertThat(total.prorate(1, 3)).isEqualTo(Money.of("33.33"));
            assertThat(total.prorate(2, 3)).isEqualTo(Money.of("66.67"));

            // Three naive equal thirds lose a paisa — which is exactly why callers must give the
            // final slice the remainder rather than summing independent shares.
            Money naiveSum = total.prorate(1, 3).multiply(3);
            assertThat(naiveSum).isNotEqualTo(total);
            assertThat(naiveSum).isEqualTo(Money.of("99.99"));
        }

        @Test
        @DisplayName("splits by money ratio, used to apportion an order discount across lines")
        void proratesByRatio() {
            Money discount = Money.of("100.00");
            Money base = Money.of("1000.00");

            assertThat(discount.prorate(new BigDecimal("250.00"), base.amount())).isEqualTo(Money.of("25.00"));
            assertThat(discount.prorate(new BigDecimal("333.33"), base.amount())).isEqualTo(Money.of("33.33"));
        }

        @Test
        @DisplayName("returns zero rather than dividing by zero on an empty base")
        void handlesZeroBase() {
            assertThat(Money.of("100.00").prorate(BigDecimal.TEN, BigDecimal.ZERO)).isEqualTo(Money.zero());
        }
    }

    @Nested
    @DisplayName("comparison")
    class Comparison {

        @Test
        @DisplayName("min, max, and the ordering predicates agree with each other")
        void comparisons() {
            Money small = Money.of("10.00");
            Money large = Money.of("20.00");

            assertThat(small.min(large)).isEqualTo(small);
            assertThat(small.max(large)).isEqualTo(large);
            assertThat(large.isGreaterThan(small)).isTrue();
            assertThat(small.isLessThan(large)).isTrue();
            assertThat(small.isGreaterThanOrEqualTo(Money.of("10.00"))).isTrue();
            assertThat(small.compareTo(large)).isNegative();
        }

        @Test
        @DisplayName("zero, positive, and negative predicates")
        void signPredicates() {
            assertThat(Money.zero().isZero()).isTrue();
            assertThat(Money.of("0.01").isPositive()).isTrue();
            assertThat(Money.of("-0.01").isNegative()).isTrue();
        }
    }
}
