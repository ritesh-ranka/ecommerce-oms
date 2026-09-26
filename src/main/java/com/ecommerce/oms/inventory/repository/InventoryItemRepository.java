package com.ecommerce.oms.inventory.repository;

import com.ecommerce.oms.inventory.domain.InventoryItem;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Stock data access. The locking queries here are the concurrency control for the entire
 * system, so they are worth reading closely.
 */
public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    /** Unlocked read, for availability display. Never used on a write path. */
    @Query("""
            select i from InventoryItem i
            join fetch i.warehouse
            where i.variant.id = :variantId
            order by i.id asc
            """)
    List<InventoryItem> findByVariantId(@Param("variantId") Long variantId);

    /** Candidate discovery before locking: returns row ids only, no lock held yet. */
    @Query("select i.id from InventoryItem i where i.variant.id in :variantIds order by i.id asc")
    List<Long> findIdsByVariantIds(@Param("variantIds") Collection<Long> variantIds);

    /**
     * Acquires {@code SELECT ... FOR UPDATE} on every candidate row in one statement.
     *
     * <p>Three things make this the linchpin of the oversell guarantee:
     *
     * <ol>
     *   <li><b>Pessimistic write locks</b> serialise concurrent transactions that touch the
     *       same SKU, so the read-check-write cycle in {@code ReservationService} cannot
     *       interleave. Without this, fifty threads can all read {@code available = 1} and all
     *       decide they may proceed.</li>
     *   <li><b>{@code order by i.id asc}</b> gives every caller the same global lock
     *       acquisition order. Two carts containing the same two SKUs in opposite order would
     *       otherwise each hold the lock the other needs, and deadlock. Ordering makes that
     *       impossible by construction rather than by retry.</li>
     *   <li><b>One statement for all rows</b> means the ordering actually holds. Locking in a
     *       loop would let another transaction interleave between iterations, reintroducing the
     *       deadlock the ordering was meant to prevent.</li>
     * </ol>
     *
     * <p>The lock timeout is bounded (10s, set on the JDBC URL) so a pathological case fails
     * fast with a 409 instead of occupying a request thread indefinitely.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select i from InventoryItem i where i.id in :ids order by i.id asc")
    List<InventoryItem> lockAllByIdInOrder(@Param("ids") List<Long> ids);

    Optional<InventoryItem> findByVariantIdAndWarehouseId(Long variantId, Long warehouseId);

    @Query("""
            select coalesce(sum(i.onHand - i.reserved), 0) from InventoryItem i
            where i.variant.id = :variantId
            """)
    int totalAvailableForVariant(@Param("variantId") Long variantId);

    @Query("""
            select i from InventoryItem i
            join fetch i.variant v
            join fetch v.product
            join fetch i.warehouse
            where i.onHand - i.reserved <= i.reorderLevel
            order by i.id asc
            """)
    List<InventoryItem> findLowStock();

    @Query("""
            select i from InventoryItem i
            join fetch i.variant v
            join fetch v.product
            join fetch i.warehouse w
            where (:warehouseId is null or w.id = :warehouseId)
              and (:variantId is null or v.id = :variantId)
            order by i.id asc
            """)
    List<InventoryItem> search(@Param("variantId") Long variantId,
                               @Param("warehouseId") Long warehouseId);
}
