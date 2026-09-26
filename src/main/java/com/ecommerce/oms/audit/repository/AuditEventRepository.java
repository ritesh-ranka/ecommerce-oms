package com.ecommerce.oms.audit.repository;

import com.ecommerce.oms.audit.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findByEntityTypeAndEntityIdOrderByIdDesc(
            String entityType, Long entityId, Pageable pageable);

    @Query("""
            select a from AuditEvent a
            where (:entityType is null or a.entityType = :entityType)
              and (:action is null or a.action = :action)
            order by a.id desc
            """)
    Page<AuditEvent> search(@Param("entityType") String entityType,
                            @Param("action") String action,
                            Pageable pageable);
}
