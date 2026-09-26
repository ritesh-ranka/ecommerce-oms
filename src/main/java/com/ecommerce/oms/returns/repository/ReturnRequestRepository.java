package com.ecommerce.oms.returns.repository;

import com.ecommerce.oms.returns.domain.ReturnRequest;
import com.ecommerce.oms.returns.domain.ReturnStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    @EntityGraph(attributePaths = {"lines"})
    Optional<ReturnRequest> findWithLinesById(Long id);

    @EntityGraph(attributePaths = {"lines"})
    List<ReturnRequest> findByOrderIdOrderByIdDesc(Long orderId);

    Page<ReturnRequest> findByStatusOrderByIdAsc(ReturnStatus status, Pageable pageable);

    boolean existsByOrderIdAndStatus(Long orderId, ReturnStatus status);

    /**
     * Units of one order line already committed to approved or in-flight returns.
     *
     * <p>Counts {@code REQUESTED} as well as {@code APPROVED} on purpose: two pending requests for the
     * same units would each look valid in isolation and together return more than was bought. Rejected
     * requests release their claim, which is why they are excluded.
     */
    @Query("""
            select coalesce(sum(l.quantity), 0) from ReturnLine l
            where l.orderLineId = :orderLineId
              and l.returnRequest.status <> com.ecommerce.oms.returns.domain.ReturnStatus.REJECTED
            """)
    int unitsClaimedForOrderLine(@Param("orderLineId") Long orderLineId);

    /** Already-refunded value for one order line, used to make the final slice absorb rounding. */
    @Query("""
            select coalesce(sum(l.refund.amount), 0) from ReturnLine l
            where l.orderLineId = :orderLineId
              and l.returnRequest.status = com.ecommerce.oms.returns.domain.ReturnStatus.APPROVED
            """)
    BigDecimal refundedAmountForOrderLine(@Param("orderLineId") Long orderLineId);
}
