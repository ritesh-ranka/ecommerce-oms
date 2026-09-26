package com.ecommerce.oms.outbox.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * A durable record that something happened and downstream work is owed.
 *
 * <p><b>Why a table and not just an in-process event.</b> Requirement: fulfillment routing,
 * notification, and audit must not block the checkout response. A plain {@code @Async} listener
 * achieves that, but loses the event entirely if the JVM dies between commit and handler execution —
 * an order confirmed with no pick task and no confirmation email, and no record that either was owed.
 * Inserting this row <em>inside</em> the checkout transaction makes the intent as durable as the order
 * itself: the two commit together or not at all.
 *
 * <p>Delivery is therefore at-least-once, never at-most-once, which is why handlers must be
 * idempotent (see {@code outbox_handler_executions}).
 *
 * <p>{@code availableAt} implements exponential backoff without a scheduler-side data structure: a
 * failed event simply becomes invisible until its next attempt is due.
 */
@Entity
@Getter
@Table(name = "outbox_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseEntity {

    /** Logical event name, e.g. {@code OrderPlaced}. Handlers subscribe by this value. */
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    /** JSON snapshot. Self-contained so a handler never has to re-read mutable state. */
    @Column(name = "payload", nullable = false, length = 4000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** Not eligible for dispatch before this instant. Drives the backoff. */
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static OutboxEvent pending(String eventType, String aggregateType,
                                      Long aggregateId, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.eventType = eventType;
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.payload = payload;
        event.status = OutboxStatus.PENDING;
        event.attempts = 0;
        event.availableAt = Instant.now();
        return event;
    }

    public void markProcessed() {
        this.status = OutboxStatus.PROCESSED;
        this.processedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Records a failed attempt and schedules the next one, or gives up.
     *
     * <p>Backoff is {@code base x 2^attempts}, capped at five minutes. Capping matters: without it,
     * the tenth retry of a long-lived outage would be scheduled days out, which is indistinguishable
     * from losing the event.
     */
    public void recordFailure(String error, int maxAttempts, Duration baseBackoff) {
        this.attempts++;
        this.lastError = truncate(error);

        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.DEAD_LETTER;
            return;
        }

        long multiplier = 1L << Math.min(this.attempts, 8);
        Duration delay = baseBackoff.multipliedBy(multiplier);
        Duration capped = delay.compareTo(Duration.ofMinutes(5)) > 0 ? Duration.ofMinutes(5) : delay;
        this.availableAt = Instant.now().plus(capped);
    }

    /** Lets the retry scheduler re-offer an event abandoned by a dispatcher that died mid-flight. */
    public void reschedule(Instant when) {
        this.availableAt = when;
    }

    public boolean isPending() {
        return status == OutboxStatus.PENDING;
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    @Override
    public String toString() {
        return "OutboxEvent[id=%d, type=%s, aggregate=%s/%d, status=%s, attempts=%d]"
                .formatted(getId(), eventType, aggregateType, aggregateId, status, attempts);
    }
}
