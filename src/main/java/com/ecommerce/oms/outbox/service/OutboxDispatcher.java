package com.ecommerce.oms.outbox.service;

import com.ecommerce.oms.common.config.AsyncConfig;
import com.ecommerce.oms.common.config.OmsProperties;
import com.ecommerce.oms.outbox.domain.OutboxEvent;
import com.ecommerce.oms.outbox.domain.OutboxHandlerExecution;
import com.ecommerce.oms.outbox.repository.OutboxEventRepository;
import com.ecommerce.oms.outbox.repository.OutboxHandlerExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drains the outbox. This is what satisfies requirement #3 — the downstream pipeline runs without
 * blocking the customer's checkout response.
 *
 * <h2>Why {@code AFTER_COMMIT} and not just {@code @Async}</h2>
 * {@link TransactionPhase#AFTER_COMMIT} guarantees handlers never observe uncommitted state. An
 * {@code @Async} listener on a plain application event can start before the producing transaction
 * commits, and then a handler reading the order finds nothing there — a race that appears only under
 * load and is miserable to diagnose. Combining both gives the property that matters: dispatch happens
 * after the data is durable, and off the request thread.
 *
 * <h2>Why each handler gets its own transaction</h2>
 * Handlers are independent consumers of the same fact. A failing notification provider must not roll
 * back the audit row, and a duplicate-shipment conflict must not lose the customer's email. Per-handler
 * transactions also make the {@code (event, handler)} completion marker meaningful: it commits with the
 * work it describes, so it can never claim success for work that was rolled back.
 */
@Slf4j
@Service
public class OutboxDispatcher {

    private final OutboxEventRepository outboxRepository;
    private final OutboxHandlerExecutionRepository executionRepository;
    private final OutboxDispatcher self;
    private final Map<String, OutboxHandler> handlersByName;
    private final int maxAttempts;
    private final Duration baseBackoff;

    /**
     * @param self injected lazily so the per-handler {@code REQUIRES_NEW} methods go through the Spring
     *             proxy. A direct {@code this.method()} call would bypass the proxy and silently run
     *             everything in one transaction, defeating the isolation described above.
     */
    public OutboxDispatcher(OutboxEventRepository outboxRepository,
                            OutboxHandlerExecutionRepository executionRepository,
                            List<OutboxHandler> handlers,
                            @org.springframework.context.annotation.Lazy OutboxDispatcher self,
                            OmsProperties properties) {
        this.outboxRepository = outboxRepository;
        this.executionRepository = executionRepository;
        this.self = self;
        this.handlersByName = handlers.stream()
                .collect(Collectors.toMap(OutboxHandler::handlerName, Function.identity()));
        this.maxAttempts = properties.outbox().maxAttempts();
        this.baseBackoff = Duration.ofMillis(properties.outbox().retryIntervalMs());

        log.info("Outbox handlers registered: {}", handlersByName.keySet());
    }

    // =================================================================================
    // Prompt path: triggered by the producing transaction committing
    // =================================================================================

    /**
     * Dispatches an event as soon as its producing transaction commits.
     *
     * <p>Nothing depends on this firing. If the JVM dies before the listener runs, the row is still
     * PENDING and {@code OutboxRetryScheduler} picks it up. The listener exists purely so the customer's
     * confirmation email does not wait for the next scheduler tick.
     */
    @Async(AsyncConfig.OUTBOX_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventRecorded(OutboxRecorder.OutboxEventRecorded recorded) {
        log.debug("Dispatching outbox event {} ({}) after commit",
                recorded.outboxEventId(), recorded.eventType());
        try {
            dispatch(recorded.outboxEventId());
        } catch (Exception failure) {
            // Never propagate: an exception here would be swallowed by the executor anyway, and the
            // retry scheduler is the real safety net.
            log.error("Immediate dispatch of outbox event {} failed; the retry scheduler will re-offer it",
                    recorded.outboxEventId(), failure);
        }
    }

    // =================================================================================
    // Dispatch
    // =================================================================================

    /**
     * Runs every interested handler for one event, then marks the event processed or schedules a retry.
     *
     * <p>Reads the committed row rather than trusting an in-memory copy, so a concurrent retry that
     * already completed the event is detected instead of duplicated.
     */
    public void dispatch(Long outboxEventId) {
        OutboxEvent event = outboxRepository.findById(outboxEventId).orElse(null);
        if (event == null) {
            log.warn("Outbox event {} no longer exists", outboxEventId);
            return;
        }
        if (!event.isPending()) {
            log.debug("Outbox event {} is already {}; nothing to do", outboxEventId, event.getStatus());
            return;
        }

        List<OutboxHandler> interested = handlersByName.values().stream()
                .filter(handler -> handler.handles(event.getEventType()))
                .sorted(java.util.Comparator.comparing(OutboxHandler::handlerName))
                .toList();

        if (interested.isEmpty()) {
            log.debug("No handler consumes {}; marking event {} processed",
                    event.getEventType(), outboxEventId);
            self.markProcessed(outboxEventId);
            return;
        }

        Set<String> alreadyDone = Set.copyOf(executionRepository.findHandlerNamesFor(outboxEventId));
        List<String> failures = new ArrayList<>();

        for (OutboxHandler handler : interested) {
            if (alreadyDone.contains(handler.handlerName())) {
                log.debug("Handler {} already completed event {}; skipping (idempotent replay)",
                        handler.handlerName(), outboxEventId);
                continue;
            }
            try {
                self.runHandler(handler, event);
                log.debug("Handler {} completed event {} ({})",
                        handler.handlerName(), outboxEventId, event.getEventType());
            } catch (Exception failure) {
                // One handler failing must not stop the others; a broken notification provider should
                // not also prevent the audit trail from being written.
                log.error("Handler {} failed for event {} ({}): {}",
                        handler.handlerName(), outboxEventId, event.getEventType(),
                        failure.getMessage(), failure);
                failures.add(handler.handlerName() + ": " + failure.getMessage());
            }
        }

        if (failures.isEmpty()) {
            self.markProcessed(outboxEventId);
            log.info("Outbox event {} ({}) processed by {} handler(s)",
                    outboxEventId, event.getEventType(), interested.size());
        } else {
            self.recordFailure(outboxEventId, String.join(" | ", failures));
        }
    }

    // =================================================================================
    // Transactional units
    // =================================================================================

    /**
     * Runs one handler and records its completion in the same transaction.
     *
     * <p>Atomicity between the work and its marker is the point: if the handler succeeds but the marker
     * is not written, a retry duplicates the work; if the marker is written but the handler rolled back,
     * the work is silently skipped forever. Committing both together makes either outcome impossible.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runHandler(OutboxHandler handler, OutboxEvent event) {
        handler.handle(event);
        executionRepository.save(OutboxHandlerExecution.of(event.getId(), handler.handlerName()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(Long outboxEventId) {
        outboxRepository.findById(outboxEventId).ifPresent(event -> {
            if (event.isPending()) {
                event.markProcessed();
                outboxRepository.save(event);
            }
        });
    }

    /**
     * Records the failure and schedules the next attempt, or promotes to {@code DEAD_LETTER}.
     *
     * <p>Dead letters are queryable by an admin rather than discarded. A poison event that disappears
     * silently is strictly worse than one sitting in a queue someone can look at.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long outboxEventId, String error) {
        outboxRepository.findById(outboxEventId).ifPresent(event -> {
            event.recordFailure(error, maxAttempts, baseBackoff);
            outboxRepository.save(event);

            if (event.getStatus().isTerminal()) {
                log.error("Outbox event {} ({}) moved to DEAD_LETTER after {} attempt(s): {}",
                        outboxEventId, event.getEventType(), event.getAttempts(), error);
            } else {
                log.warn("Outbox event {} ({}) attempt {} failed; next attempt at {}",
                        outboxEventId, event.getEventType(), event.getAttempts(), event.getAvailableAt());
            }
        });
    }

    /** Operational visibility, used by the admin endpoint and by tests. */
    public Set<String> registeredHandlers() {
        return handlersByName.keySet();
    }
}
