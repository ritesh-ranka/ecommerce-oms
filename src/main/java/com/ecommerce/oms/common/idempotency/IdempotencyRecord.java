package com.ecommerce.oms.common.idempotency;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row per (key, endpoint). The unique constraint on that pair is the concurrency
 * control: two simultaneous submits race on INSERT and exactly one wins.
 */
@Entity
@Getter
@Table(name = "idempotency_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord extends BaseEntity {

    public enum State {
        /** Handler is running. A concurrent submit with the same key is rejected as 409. */
        IN_PROGRESS,
        /** Handler finished; {@link #responseBody} can be replayed. */
        COMPLETED
    }

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "endpoint", nullable = false, length = 160)
    private String endpoint;

    @Column(name = "user_id")
    private Long userId;

    /** SHA-256 of the request body, so key reuse with a different payload is detectable. */
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private State state;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", length = 4000)
    private String responseBody;

    public static IdempotencyRecord inProgress(String key, String endpoint, Long userId, String fingerprint) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.idempotencyKey = key;
        record.endpoint = endpoint;
        record.userId = userId;
        record.requestFingerprint = fingerprint;
        record.state = State.IN_PROGRESS;
        return record;
    }

    public void complete(int status, String body) {
        this.state = State.COMPLETED;
        this.responseStatus = status;
        this.responseBody = body;
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean matches(String fingerprint) {
        return this.requestFingerprint.equals(fingerprint);
    }
}
