package com.ecommerce.oms.outbox.repository;

import com.ecommerce.oms.outbox.domain.OutboxEvent;
import com.ecommerce.oms.outbox.domain.OutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Events whose next attempt is due.
     *
     * <p>Filtering on {@code availableAt} is what implements the backoff: a recently failed event is
     * simply not returned yet. Paged so one sweep cannot try to drain an unbounded backlog inside a
     * single transaction.
     */
    @Query("""
            select e from OutboxEvent e
            where e.status = com.ecommerce.oms.outbox.domain.OutboxStatus.PENDING
              and e.availableAt <= :now
            order by e.id asc
            """)
    List<OutboxEvent> findDue(@Param("now") Instant now, Pageable pageable);

    Page<OutboxEvent> findByStatusOrderByIdDesc(OutboxStatus status, Pageable pageable);

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByIdAsc(String aggregateType, Long aggregateId);
}
