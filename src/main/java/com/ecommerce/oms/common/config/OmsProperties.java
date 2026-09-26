package com.ecommerce.oms.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Every tunable business constant in the system, bound from the {@code oms.*} namespace.
 *
 * <p>Nothing in the domain hard-codes a TTL, a tax rate, a retry count, or a fee. That is
 * not decoration: the concurrency test shortens the reservation TTL, and the integration
 * tests shorten the outbox retry interval, both purely through configuration.
 */
@ConfigurationProperties(prefix = "oms")
public record OmsProperties(
        Security security,
        Inventory inventory,
        Pricing pricing,
        Returns returns,
        Outbox outbox,
        Idempotency idempotency
) {

    public record Security(Jwt jwt) {
        public record Jwt(
                @NotBlank String secret,
                @NotBlank String issuer,
                Duration accessTokenTtl
        ) {
        }
    }

    public record Inventory(
            /** How long a PENDING reservation holds stock before the sweeper releases it. */
            Duration reservationTtl,
            long sweeperIntervalMs,
            /** SINGLE_WAREHOUSE_FIRST or SPLIT_ACROSS_WAREHOUSES. */
            String allocationStrategy,
            @Positive int maxOptimisticRetries
    ) {
    }

    public record Pricing(
            String currency,
            /** Fraction, not percentage: 0.18 means 18%. */
            BigDecimal defaultTaxRate,
            BigDecimal flatShippingFee,
            BigDecimal freeShippingThreshold
    ) {
    }

    public record Returns(int windowDays) {
    }

    public record Outbox(
            long retryIntervalMs,
            int maxAttempts,
            /** A PENDING event older than this is considered abandoned and is retried. */
            Duration staleAfter
    ) {
    }

    public record Idempotency(Duration retention) {
    }
}
