package com.ecommerce.oms.inventory.repository;

import com.ecommerce.oms.inventory.domain.ReservationStatus;
import com.ecommerce.oms.inventory.domain.StockReservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findByReference(String reference);

    List<StockReservation> findByOrderId(Long orderId);

    List<StockReservation> findByOrderIdAndStatus(Long orderId, ReservationStatus status);

    @Query("select r from StockReservation r where r.id in :ids order by r.id asc")
    List<StockReservation> findAllByIdInOrder(@Param("ids") Collection<Long> ids);

    /**
     * Expired-but-still-holding reservations, oldest first.
     *
     * <p>Paged so a large backlog is drained over several sweeper runs rather than in one
     * transaction that locks thousands of inventory rows at once.
     */
    @Query("""
            select r from StockReservation r
            where r.status = com.ecommerce.oms.inventory.domain.ReservationStatus.PENDING
              and r.expiresAt < :now
            order by r.expiresAt asc
            """)
    List<StockReservation> findExpired(@Param("now") Instant now, Pageable pageable);

    long countByStatus(ReservationStatus status);
}
