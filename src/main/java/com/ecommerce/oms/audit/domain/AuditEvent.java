package com.ecommerce.oms.audit.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An append-only record of who did what to which entity.
 *
 * <p>Distinct from the application log on purpose: logs rotate, are unqueryable, and are not part of
 * the transactional record. "Who cancelled this order and when" is a question support and finance ask
 * about real money, so the answer belongs in a table that can be joined and retained.
 *
 * <p>{@code traceId} ties a row back to the exact request that produced it, which is what lets someone
 * move from an audit entry to the full log context of the operation.
 */
@Entity
@Getter
@Table(name = "audit_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent extends BaseEntity {

    /** Null for system actors — the reservation sweeper and the retry scheduler have no user. */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_email", length = 190)
    private String actorEmail;

    @Column(name = "action", nullable = false, length = 80)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "from_state", length = 40)
    private String fromState;

    @Column(name = "to_state", length = 40)
    private String toState;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "trace_id", length = 40)
    private String traceId;

    public static AuditEvent of(Long actorId, String actorEmail, String action,
                                String entityType, Long entityId,
                                String fromState, String toState, String reason, String traceId) {
        AuditEvent event = new AuditEvent();
        event.actorId = actorId;
        event.actorEmail = actorEmail;
        event.action = action;
        event.entityType = entityType;
        event.entityId = entityId;
        event.fromState = fromState;
        event.toState = toState;
        event.reason = truncate(reason, 500);
        event.traceId = traceId;
        return event;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public String toString() {
        return "Audit[%s %s/%s %s->%s by %s]".formatted(
                action, entityType, entityId, fromState, toState, actorEmail);
    }
}
