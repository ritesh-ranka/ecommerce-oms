package com.ecommerce.oms.outbox.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Marks that one handler has already processed one event.
 *
 * <p>This is the deduplication key that makes at-least-once delivery safe. Without it, an event whose
 * third handler failed would, on retry, re-run the first two — sending a second confirmation email and
 * creating a duplicate shipment. The unique constraint on {@code (outbox_event_id, handler_name)} means
 * the guarantee is enforced by the database rather than by the dispatcher remembering to check.
 */
@Entity
@Getter
@Table(name = "outbox_handler_executions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_handler_execution",
                columnNames = {"outbox_event_id", "handler_name"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxHandlerExecution extends BaseEntity {

    @Column(name = "outbox_event_id", nullable = false)
    private Long outboxEventId;

    @Column(name = "handler_name", nullable = false, length = 80)
    private String handlerName;

    public static OutboxHandlerExecution of(Long outboxEventId, String handlerName) {
        OutboxHandlerExecution execution = new OutboxHandlerExecution();
        execution.outboxEventId = outboxEventId;
        execution.handlerName = handlerName;
        return execution;
    }
}
