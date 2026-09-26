package com.ecommerce.oms.outbox.service;

import com.ecommerce.oms.outbox.domain.OutboxEvent;
import com.ecommerce.oms.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Producer side of the transactional outbox.
 *
 * <p><b>Must be called inside the business transaction.</b> That is the entire mechanism: the outbox
 * row and the order commit together, so there is no window in which an order exists without its
 * downstream work being owed. Propagation is deliberately {@code MANDATORY} — a caller that forgot to
 * be transactional fails loudly at the first call rather than silently degrading to best-effort
 * delivery that loses events on a crash.
 *
 * <p>It also publishes a Spring application event. The two are complementary, not redundant: the row
 * is the durable record of intent, and the in-process event is what triggers prompt dispatch after
 * commit so the customer's confirmation email does not wait for the next scheduler tick. If the
 * process dies before the listener runs, the row is still there and the retry scheduler picks it up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRecorder {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Records an event for asynchronous delivery.
     *
     * @param eventType     logical name handlers subscribe to, e.g. {@code OrderPlaced}
     * @param aggregateType owning aggregate, e.g. {@code Order}
     * @param aggregateId   owning aggregate id
     * @param payload       serialisable snapshot; must be self-contained so a handler never
     *                      re-reads state that may have moved on by the time it runs
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public OutboxEvent record(String eventType, String aggregateType, Long aggregateId, Object payload) {
        String json = serialise(eventType, payload);
        OutboxEvent saved = outboxRepository.save(
                OutboxEvent.pending(eventType, aggregateType, aggregateId, json));

        log.debug("Outbox row queued in-transaction: id={} type={} aggregate={}/{}",
                saved.getId(), eventType, aggregateType, aggregateId);

        // Consumed by an AFTER_COMMIT listener, so handlers never observe uncommitted state.
        eventPublisher.publishEvent(new OutboxEventRecorded(saved.getId(), eventType));
        return saved;
    }

    private String serialise(String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > 4000) {
                throw new IllegalArgumentException(
                        "Outbox payload for %s is %d chars, exceeding the 4000 column limit. "
                                .formatted(eventType, json.length())
                                + "Keep payloads to identifiers and summary fields.");
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Outbox payload for %s is not serialisable".formatted(eventType), failure);
        }
    }

    /**
     * Signal that a row was written. Carries only the id: the listener re-reads the committed row, so
     * it cannot act on a stale in-memory copy.
     */
    public record OutboxEventRecorded(Long outboxEventId, String eventType) {
    }
}
