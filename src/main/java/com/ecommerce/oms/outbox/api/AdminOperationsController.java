package com.ecommerce.oms.outbox.api;

import com.ecommerce.oms.audit.service.AuditService;
import com.ecommerce.oms.audit.service.AuditService.AuditView;
import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.outbox.domain.OutboxEvent;
import com.ecommerce.oms.outbox.domain.OutboxStatus;
import com.ecommerce.oms.outbox.repository.OutboxEventRepository;
import com.ecommerce.oms.outbox.service.OutboxDispatcher;
import com.ecommerce.oms.payment.domain.PaymentStatus;
import com.ecommerce.oms.payment.repository.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Operational visibility into the asynchronous machinery.
 *
 * <p>The brief excludes production observability, and this is not an attempt to smuggle it back in. It is
 * the minimum needed for the async design to be <em>defensible</em>: a transactional outbox that silently
 * accumulates dead letters is worse than no outbox at all, because the failure is invisible. Three
 * numbers and a list make the difference between "events are retried" as a claim and as something you can
 * check.
 */
@Tag(name = "11. Admin — Operations", description = "Outbox, audit trail, and payment reconciliation (ADMIN)")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class AdminOperationsController {

    private final OutboxEventRepository outboxRepository;
    private final OutboxDispatcher outboxDispatcher;
    private final AuditService auditService;
    private final PaymentRepository paymentRepository;

    // ------------------------------------------------------------------ outbox

    @GetMapping("/outbox/stats")
    @Operation(summary = "Outbox health at a glance",
            description = """
                    Counts by status plus the registered handlers. A non-zero `DEAD_LETTER` count is the
                    signal that downstream work has been abandoned and needs a human.
                    """)
    public ResponseEntity<ApiResponse<Map<String, Object>>> outboxStats() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "pending", outboxRepository.countByStatus(OutboxStatus.PENDING),
                "processed", outboxRepository.countByStatus(OutboxStatus.PROCESSED),
                "deadLetter", outboxRepository.countByStatus(OutboxStatus.DEAD_LETTER),
                "registeredHandlers", outboxDispatcher.registeredHandlers(),
                "checkedAt", Instant.now())));
    }

    @GetMapping("/outbox/dead-letters")
    @Operation(summary = "Events that exhausted their retries",
            description = """
                    Each row carries `attempts` and `lastError`, naming the handler that failed. A poison
                    event is visible here rather than silently discarded — which is the whole reason the
                    DEAD_LETTER state exists.
                    """)
    public ResponseEntity<ApiResponse<PageResponse<DeadLetterView>>> deadLetters(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                outboxRepository.findByStatusOrderByIdDesc(OutboxStatus.DEAD_LETTER, pageable),
                DeadLetterView::from)));
    }

    @PostMapping("/outbox/{eventId}/redeliver")
    @Operation(summary = "Manually re-offer an event",
            description = """
                    Re-runs the handlers that have not yet completed for this event. Handlers that already
                    succeeded are skipped via the `(event, handler)` execution marker, so redelivering will
                    not duplicate a notification or a shipment.

                    The intended use is after fixing whatever made a dead letter fail.
                    """)
    public ResponseEntity<ApiResponse<Void>> redeliver(@PathVariable Long eventId) {
        outboxDispatcher.dispatch(eventId);
        return ResponseEntity.ok(ApiResponse.message("Redelivery attempted for outbox event " + eventId));
    }

    // ------------------------------------------------------------------ audit

    @GetMapping("/audit-events")
    @Operation(summary = "Search the audit trail",
            description = """
                    Filter by `entityType` and/or `action`. Every row carries the `traceId` of the request
                    that caused it, so an audit entry leads straight to the full log context — including for
                    rows written asynchronously minutes later.
                    """)
    public ResponseEntity<ApiResponse<PageResponse<AuditView>>> auditEvents(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(auditService.search(entityType, action, pageable)));
    }

    @GetMapping("/audit-events/{entityType}/{entityId}")
    @Operation(summary = "Full audit history for one entity",
            description = "For example `Order/1` — every status change, cancellation, and return, newest first.")
    public ResponseEntity<ApiResponse<PageResponse<AuditView>>> auditForEntity(
            @PathVariable String entityType,
            @PathVariable Long entityId,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                auditService.forEntity(entityType, entityId, pageable)));
    }

    // ------------------------------------------------------------------ payment reconciliation

    @GetMapping("/payments/unconfirmed")
    @Operation(summary = "Payments whose outcome is unknown",
            description = """
                    The reconciliation queue. A gateway timeout leaves the payment `UNCONFIRMED` because we
                    genuinely cannot tell whether the money moved — stock stays held and the order stays
                    `AWAITING_PAYMENT` until the reservation TTL resolves it.

                    These rows are what an operator checks against the provider's records. In a production
                    build this is where a webhook or a reconciliation job would settle them.
                    """)
    public ResponseEntity<ApiResponse<PageResponse<UnconfirmedPaymentView>>> unconfirmedPayments(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                paymentRepository.findByStatusOrderByIdDesc(PaymentStatus.UNCONFIRMED, pageable),
                payment -> new UnconfirmedPaymentView(payment.getId(), payment.getOrderId(),
                        payment.getCharged().toString(), payment.getMethod().name(),
                        payment.getFailureReason(), payment.getCreatedAt()))));
    }

    // ------------------------------------------------------------------ view models

    public record DeadLetterView(
            Long id,
            String eventType,
            String aggregateType,
            Long aggregateId,
            int attempts,
            String lastError,
            String payload,
            Instant firstQueuedAt
    ) {
        static DeadLetterView from(OutboxEvent event) {
            return new DeadLetterView(event.getId(), event.getEventType(), event.getAggregateType(),
                    event.getAggregateId(), event.getAttempts(), event.getLastError(),
                    event.getPayload(), event.getCreatedAt());
        }
    }

    public record UnconfirmedPaymentView(
            Long paymentId,
            Long orderId,
            String amount,
            String method,
            String gatewayMessage,
            Instant attemptedAt
    ) {
    }
}
