package com.ecommerce.oms.catalog.repository;

import com.ecommerce.oms.catalog.domain.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * {@link JpaSpecificationExecutor} is what lets catalog filters compose. The alternative —
 * a derived query method per filter combination — grows combinatorially and is the usual
 * reason catalog repositories end up with thirty methods.
 */
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /** Detail view: one query, variants and category eagerly attached. */
    @EntityGraph(attributePaths = {"category", "variants"})
    Optional<Product> findWithVariantsById(Long id);

    boolean existsByCategoryId(Long categoryId);
}
