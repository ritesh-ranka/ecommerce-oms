package com.ecommerce.oms.returns.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.domain.Money;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One order line, partially or wholly returned.
 *
 * <p>{@code refund} is the amount attributable to <em>these</em> units, computed from the order line's
 * persisted snapshot so the discount share and tax that were actually charged are reversed. Storing the
 * figure rather than recomputing it later matters because the coupon may have since expired or changed,
 * and the refund has to remain explainable years after the fact.
 *
 * <p>{@code orderLineId} is a plain column: {@code returns} should not acquire a compile-time dependency
 * on {@code order}'s entity graph to record which line came back.
 */
@Entity
@Getter
@Table(name = "return_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_request_id", nullable = false)
    private ReturnRequest returnRequest;

    @Column(name = "order_line_id", nullable = false)
    private Long orderLineId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Maps to refund_amount / refund_currency. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "refund_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "refund_currency"))
    })
    private Money refund;

    static ReturnLine of(ReturnRequest request, Long orderLineId, int quantity, Money refund) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Return quantity must be positive, got " + quantity);
        }
        ReturnLine line = new ReturnLine();
        line.returnRequest = request;
        line.orderLineId = orderLineId;
        line.quantity = quantity;
        line.refund = refund;
        return line;
    }
}
