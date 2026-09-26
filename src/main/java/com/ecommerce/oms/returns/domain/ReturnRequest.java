package com.ecommerce.oms.returns.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.common.error.ApiException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer's request to send goods back, covering one or more lines of one order.
 *
 * <p>Modelled as an aggregate rather than a flag on the order because returns are genuinely plural: a
 * customer can return two of five shirts today and two more next week, each with its own reason,
 * decision, and refund. A boolean or a single quantity on the order could not express that, and could not
 * answer "what did we actually refund, and why" afterwards.
 *
 * <p>{@code @Version} guards against two staff members approving the same request at once — which would
 * otherwise restock twice and refund twice.
 */
@Entity
@Getter
@Table(name = "return_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnRequest extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ReturnStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private ReturnReason reason;

    @Column(name = "comment", length = 500)
    private String comment;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReturnLine> lines = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static ReturnRequest requested(Long orderId, ReturnReason reason, String comment) {
        ReturnRequest request = new ReturnRequest();
        request.orderId = orderId;
        request.status = ReturnStatus.REQUESTED;
        request.reason = reason;
        request.comment = truncate(comment, 500);
        request.requestedAt = Instant.now();
        return request;
    }

    public ReturnLine addLine(Long orderLineId, int quantity, Money refund) {
        ReturnLine line = ReturnLine.of(this, orderLineId, quantity, refund);
        this.lines.add(line);
        return line;
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Approves the request.
     *
     * <p>Refuses if the request has already been resolved. That guard is what makes a double-submitted
     * approval safe: the second call fails loudly instead of restocking and refunding a second time.
     */
    public void approve(String note) {
        assertPending(ReturnStatus.APPROVED);
        this.status = ReturnStatus.APPROVED;
        this.resolutionNote = truncate(note, 500);
        this.resolvedAt = Instant.now();
    }

    public void reject(String note) {
        assertPending(ReturnStatus.REJECTED);
        this.status = ReturnStatus.REJECTED;
        this.resolutionNote = truncate(note, 500);
        this.resolvedAt = Instant.now();
    }

    private void assertPending(ReturnStatus target) {
        if (status != ReturnStatus.REQUESTED) {
            throw ApiException.illegalTransition(
                    "Return request %d was already %s and cannot be %s".formatted(getId(), status, target));
        }
    }

    // ------------------------------------------------------------------ derived

    public int totalUnits() {
        return lines.stream().mapToInt(ReturnLine::getQuantity).sum();
    }

    /** Sum of the per-line refund amounts computed when the request was created. */
    public Money totalRefund() {
        return lines.stream()
                .map(ReturnLine::getRefund)
                .reduce(Money.zero(), Money::plus);
    }

    public boolean isPending() {
        return status.isPending();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public String toString() {
        return "ReturnRequest[id=%d, orderId=%d, status=%s, units=%d, refund=%s]"
                .formatted(getId(), orderId, status, totalUnits(), totalRefund());
    }
}
