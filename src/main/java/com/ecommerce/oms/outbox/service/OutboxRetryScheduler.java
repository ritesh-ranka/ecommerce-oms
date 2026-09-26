package com.ecommerce.oms.outbox.service;

import com.ecommerce.oms.outbox.domain.OutboxEvent;
import com.ecommerce.oms.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Re-offers outbox events that were never delivered.
 *
 * <p>This is the component that turns "we tried to send it" into "it will be sent". Three situations
 * produce a PENDING row that nothing else will pick up:
 *
 * <ul>
 *   <li>a handler failed and the event was scheduled for a later attempt;</li>
 *   <li>the JVM died between the producing commit and the {@code AFTER_COMMIT} listener;</li>
 *   <li>the async pool was saturated and the dispatch was run by the caller, then interrupted.</li>
 * </ul>
 *
 * <p>Without this job the outbox would be a durable record of work nobody ever does, which is a more
 * insidious failure than losing the event outright — the row is right there, looking handled.
 *
 * <p>Eligibility is driven entirely by {@code available_at <= now}, so the exponential backoff written
 * by {@link OutboxEvent#recordFailure} needs no scheduler-side bookkeeping.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRetryScheduler {

    /** Bounded so one tick cannot monopolise the executor or the connection pool. */
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxRepository;
    private final OutboxDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${oms.outbox.retry-interval-ms}", initialDelay = 10_000)
    public void retryDueEvents() {
        try {
            List<OutboxEvent> due = outboxRepository.findDue(Instant.now(), PageRequest.of(0, BATCH_SIZE));
            if (due.isEmpty()) {
                return;
            }

            log.info("Outbox retry sweep: {} event(s) due", due.size());
            for (OutboxEvent event : due) {
                try {
                    dispatcher.dispatch(event.getId());
                } catch (Exception failure) {
                    // Keep going: one poison event must not stall the rest of the batch.
                    log.error("Retry of outbox event {} threw", event.getId(), failure);
                }
            }
        } catch (Exception failure) {
            // Swallowed deliberately — a propagated exception can silently kill the schedule, and then
            // nothing would ever retry again.
            log.error("Outbox retry sweep failed; will run again on the next tick", failure);
        }
    }
}
