package com.ecommerce.oms.catalog.repository;

import com.ecommerce.oms.catalog.domain.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    Optional<ProductVariant> findBySkuIgnoreCase(String sku);

    boolean existsBySkuIgnoreCase(String sku);

    /**
     * Batch-loads variants for a page of products. Deliberately a second query rather than
     * a join fetch: joining a collection and paginating in one statement forces Hibernate
     * to paginate in memory, which is silently wrong once the catalog grows.
     */
    @Query("select v from ProductVariant v where v.product.id in :productIds order by v.id asc")
    List<ProductVariant> findByProductIdIn(@Param("productIds") Collection<Long> productIds);

    @Query("""
            select v from ProductVariant v
            join fetch v.product p
            join fetch p.category
            where v.id = :id
            """)
    Optional<ProductVariant> findWithProductById(@Param("id") Long id);

    @Query("""
            select v from ProductVariant v
            join fetch v.product p
            join fetch p.category
            where v.id in :ids
            """)
    List<ProductVariant> findAllWithProductByIdIn(@Param("ids") Collection<Long> ids);
}
