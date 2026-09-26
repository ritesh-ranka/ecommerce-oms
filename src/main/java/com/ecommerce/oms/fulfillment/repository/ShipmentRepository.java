package com.ecommerce.oms.fulfillment.repository;

import com.ecommerce.oms.fulfillment.domain.Shipment;
import com.ecommerce.oms.fulfillment.domain.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByOrderIdOrderByIdAsc(Long orderId);

    Optional<Shipment> findByOrderIdAndWarehouseId(Long orderId, Long warehouseId);

    boolean existsByOrderIdAndWarehouseId(Long orderId, Long warehouseId);

    /**
     * The staff work queue, scoped to the warehouses the caller is assigned to.
     *
     * <p>Scoping in the query rather than filtering afterwards means a staff member cannot see another
     * warehouse's queue even if a future caller forgets the check.
     */
    @Query("""
            select s from Shipment s
            where s.warehouseId in :warehouseIds
              and (:status is null or s.status = :status)
            order by s.id asc
            """)
    Page<Shipment> findQueueForWarehouses(@Param("warehouseIds") Collection<Long> warehouseIds,
                                         @Param("status") ShipmentStatus status,
                                         Pageable pageable);

    long countByWarehouseIdAndStatus(Long warehouseId, ShipmentStatus status);
}
