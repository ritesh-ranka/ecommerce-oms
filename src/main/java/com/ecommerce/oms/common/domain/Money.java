package com.ecommerce.oms.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary value object. The single place in the codebase where rounding,
 * scale, and currency compatibility are decided.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Always normalised to scale 2 with {@link RoundingMode#HALF_UP}, so two equal
 *       amounts are always {@code equals} regardless of how they were computed.</li>
 *   <li>Arithmetic across different currencies throws rather than silently producing a
 *       wrong number.</li>
 *   <li>No {@code double} or {@code float} appears anywhere in this class, or anywhere
 *       else in the system.</li>
 * </ul>
 *
 * <p>Persisted as an {@code (amount, currency)} column pair. Column names are produced by
 * {@code ImplicitNamingStrategyComponentPathImpl}, which prefixes them with the owning
 * property path — a {@code Money subtotal} field becomes {@code subtotal_amount} and
 * {@code subtotal_currency}. That is why {@code @Column} here must not set a name.
 */
@Embeddable
public final class Money implements Comparable<Money>, Serializable {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Single-currency assumption, documented in the README. Money still carries the code. */
    public static final String DEFAULT_CURRENCY = "INR";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    /** For JPA only. */
    protected Money() {
    }

    private Money(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter code, got: " + currency);
        }
        this.amount = amount.setScale(SCALE, ROUNDING);
        this.currency = currency.toUpperCase();
    }

    // ------------------------------------------------------------------ factories

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount, DEFAULT_CURRENCY);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount), DEFAULT_CURRENCY);
    }

    public static Money of(long amount) {
        return new Money(BigDecimal.valueOf(amount), DEFAULT_CURRENCY);
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO, DEFAULT_CURRENCY);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    // ------------------------------------------------------------------ accessors

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    // ------------------------------------------------------------------ arithmetic

    public Money plus(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), currency);
    }

    public Money multiply(int factor) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(this.amount.multiply(factor), currency);
    }

    /** {@code percentage(new BigDecimal("10"))} of 250.00 is 25.00. */
    public Money percentage(BigDecimal percent) {
        return new Money(this.amount.multiply(percent).divide(HUNDRED, SCALE, ROUNDING), currency);
    }

    /** {@code rate(new BigDecimal("0.18"))} of 100.00 is 18.00 — used for tax fractions. */
    public Money rate(BigDecimal fraction) {
        return new Money(this.amount.multiply(fraction), currency);
    }

    /**
     * Proportional share: {@code prorate(2, 3)} of 100.00 is 66.67.
     *
     * <p>Used for refund apportionment. The caller is responsible for making the final
     * slice absorb the rounding remainder so that the parts sum exactly to the whole;
     * see {@code ReturnService} for how that is done.
     */
    public Money prorate(int part, int whole) {
        if (whole <= 0) {
            throw new IllegalArgumentException("Cannot prorate over a non-positive whole: " + whole);
        }
        return new Money(this.amount.multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), SCALE, ROUNDING), currency);
    }

    /**
     * Proportional share using money ratios: {@code amount * (part / whole)}.
     *
     * <p>Used to apportion an order-level discount across lines by their share of the
     * subtotal. As with {@link #prorate(int, int)}, the caller must give the final slice the
     * rounding remainder so the parts sum exactly to the whole.
     */
    public Money prorate(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return zero(currency);
        }
        return new Money(this.amount.multiply(part).divide(whole, SCALE, ROUNDING), currency);
    }

    public Money negated() {
        return new Money(this.amount.negate(), currency);
    }

    public Money min(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) <= 0 ? this : other;
    }

    public Money max(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) >= 0 ? this : other;
    }

    /** Clamps negatives to zero. Discounts must never make a line total go below zero. */
    public Money atLeastZero() {
        return isNegative() ? zero(currency) : this;
    }

    // ------------------------------------------------------------------ predicates

    @JsonIgnore
    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    @JsonIgnore
    public boolean isNegative() {
        return amount.compareTo(BigDecimal.ZERO) < 0;
    }

    @JsonIgnore
    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isGreaterThan(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) >= 0;
    }

    public boolean isLessThan(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) < 0;
    }

    private void assertSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine %s with %s".formatted(this.currency, other.currency));
        }
    }

    // ------------------------------------------------------------------ identity

    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Money that
                && this.amount.compareTo(that.amount) == 0
                && this.currency.equals(that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return currency + " " + amount.toPlainString();
    }
}
