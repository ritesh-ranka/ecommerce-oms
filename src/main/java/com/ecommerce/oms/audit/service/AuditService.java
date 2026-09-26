package com.ecommerce.oms.audit.service;

import com.ecommerce.oms.audit.domain.AuditEvent;
import com.ecommerce.oms.audit.repository.AuditEventRepository;
import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.common.web.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Writes and queries the audit trail.
 *
 * <p>Picks up the trace id from MDC automatically rather than making every caller pass it. The
 * {@code AsyncConfig} task decorator propagates MDC across the thread hand-off, so an audit row written
 * by an outbox handler — minutes after the request, on a different thread — still carries the trace id of
 * the request that caused it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    @Transactional
    public AuditEvent record(Long actorId, String actorEmail, String action,
                             String entityType, Long entityId,
                             String fromState, String toState, String reason) {
        AuditEvent event = auditEventRepository.save(AuditEvent.of(
                actorId, actorEmail, action, entityType, entityId,
                fromState, toState, reason, TraceContext.currentTraceId()));

        log.debug("Audit recorded: {} {}/{} {}->{} by {}",
                action, entityType, entityId, fromState, toState,
                actorEmail == null ? "system" : actorEmail);
        return event;
    }

    /** System actor, used by the sweepers and the outbox handlers. */
    @Transactional
    public AuditEvent recordSystem(String action, String entityType, Long entityId,
                                   String fromState, String toState, String reason) {
        return record(null, null, action, entityType, entityId, fromState, toState, reason);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditView> search(String entityType, String action, Pageable pageable) {
        return PageResponse.from(
                auditEventRepository.search(entityType, action, pageable), AuditView::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditView> forEntity(String entityType, Long entityId, Pageable pageable) {
        return PageResponse.from(
                auditEventRepository.findByEntityTypeAndEntityIdOrderByIdDesc(entityType, entityId, pageable),
                AuditView::from);
    }

    public record AuditView(
            Long id,
            Long actorId,
            String actorEmail,
            String action,
            String entityType,
            Long entityId,
            String fromState,
            String toState,
            String reason,
            String traceId,
            Instant occurredAt
    ) {
        static AuditView from(AuditEvent event) {
            return new AuditView(event.getId(), event.getActorId(), event.getActorEmail(),
                    event.getAction(), event.getEntityType(), event.getEntityId(),
                    event.getFromState(), event.getToState(), event.getReason(),
                    event.getTraceId(), event.getCreatedAt());
        }
    }
}
