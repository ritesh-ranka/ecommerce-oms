package com.ecommerce.oms.order.repository;

import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = {"lines"})
    Optional<Order> findWithLinesById(Long id);

    @EntityGraph(attributePaths = {"lines"})
    Optional<Order> findWithLinesByOrderNumber(String orderNumber);

    /**
     * Ownership-scoped lookup.
     *
     * <p>Taking {@code userId} as part of the query rather than loading by id and comparing
     * afterwards means a cross-customer read returns empty and becomes a 404 — the caller cannot
     * forget the check, and the response does not confirm that someone else's order id exists.
     */
    @EntityGraph(attributePaths = {"lines"})
    Optional<Order> findWithLinesByIdAndUserId(Long id, Long userId);

    Page<Order> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    Page<Order> findByUserIdAndStatusOrderByIdDesc(Long userId, OrderStatus status, Pageable pageable);

    Page<Order> findByStatusOrderByIdDesc(OrderStatus status, Pageable pageable);

    Page<Order> findAllByOrderByIdDesc(Pageable pageable);

    boolean existsByIdAndUserId(Long id, Long userId);

    /**
     * Orders stuck awaiting payment past the reservation TTL.
     *
     * <p>Used as a backstop alongside {@code ReservationExpiredEvent}: if an order's holds expired
     * before they were ever attached to it — the window inside checkout TX-1 — no event carries its
     * id, so the sweep also looks for the orders directly.
     */
    @Query("""
            select o from Order o
            where o.status = com.ecommerce.oms.order.domain.OrderStatus.AWAITING_PAYMENT
              and o.placedAt < :cutoff
            order by o.id asc
            """)
    List<Order> findStaleAwaitingPayment(@Param("cutoff") Instant cutoff, Pageable pageable);
}
