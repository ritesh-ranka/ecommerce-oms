package com.ecommerce.oms.payment.repository;

import com.ecommerce.oms.payment.domain.Refund;
import com.ecommerce.oms.payment.domain.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByPaymentIdOrderByIdAsc(Long paymentId);

    List<Refund> findByReturnRequestId(Long returnRequestId);

    /**
     * Total successfully refunded against a payment.
     *
     * <p>Counting only {@code SUCCEEDED} rows matters: a failed attempt must not reduce the amount
     * still refundable, or a provider hiccup would silently cost the customer money.
     */
    @Query("""
            select coalesce(sum(r.refunded.amount), 0) from Refund r
            where r.payment.id = :paymentId
              and r.status = com.ecommerce.oms.payment.domain.RefundStatus.SUCCEEDED
            """)
    BigDecimal totalSucceededForPayment(@Param("paymentId") Long paymentId);

    long countByStatus(RefundStatus status);
}
