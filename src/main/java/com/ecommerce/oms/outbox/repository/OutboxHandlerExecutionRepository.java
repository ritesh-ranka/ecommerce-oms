package com.ecommerce.oms.outbox.repository;

import com.ecommerce.oms.outbox.domain.OutboxHandlerExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxHandlerExecutionRepository extends JpaRepository<OutboxHandlerExecution, Long> {

    boolean existsByOutboxEventIdAndHandlerName(Long outboxEventId, String handlerName);

    @Query("select e.handlerName from OutboxHandlerExecution e where e.outboxEventId = :eventId")
    List<String> findHandlerNamesFor(@Param("eventId") Long eventId);
}
