package com.ecommerce.oms.common.idempotency;

import com.ecommerce.oms.common.config.OmsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Transactional custody of idempotency records.
 *
 * <p>Every method runs in its own {@code REQUIRES_NEW} transaction. That is the whole
 * point: the claim must be durable <em>before</em> the business transaction starts, and it
 * must survive that transaction rolling back, otherwise two concurrent submits could both
 * believe they are the first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyStore {

    private final IdempotencyRepository repository;
    private final OmsProperties properties;

    /**
     * Attempts to claim the key. Throws {@code DataIntegrityViolationException} if another
     * request already holds it — the caller catches that and reads the existing record.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord claim(String key, String endpoint, Long userId, String fingerprint) {
        IdempotencyRecord claimed = repository.saveAndFlush(
                IdempotencyRecord.inProgress(key, endpoint, userId, fingerprint));
        log.debug("Idempotency key claimed: key={} endpoint={} recordId={}", key, endpoint, claimed.getId());
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(String key, String endpoint) {
        return repository.findByIdempotencyKeyAndEndpoint(key, endpoint);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long recordId, int status, String body) {
        repository.findById(recordId).ifPresent(record -> {
            record.complete(status, truncate(body));
            repository.save(record);
            log.debug("Idempotency record completed: recordId={} status={}", recordId, status);
        });
    }

    /**
     * Releases the claim after a failed attempt so a genuine retry is not permanently
     * locked out by a transient error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long recordId) {
        repository.findById(recordId).ifPresent(record -> {
            repository.delete(record);
            log.debug("Idempotency claim released after failure: recordId={}", recordId);
        });
    }

    /** Keeps the table from growing without bound; replay is only useful for a short window. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(properties.idempotency().retention());
        int purged = repository.deleteOlderThan(cutoff);
        if (purged > 0) {
            log.info("Purged {} idempotency record(s) older than {}", purged, cutoff);
        }
    }

    /** response_body is VARCHAR(4000); a body larger than that is not worth failing over. */
    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= 4000 ? body : body.substring(0, 4000);
    }
}
