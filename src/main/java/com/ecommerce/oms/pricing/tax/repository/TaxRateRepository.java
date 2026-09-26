package com.ecommerce.oms.pricing.tax.repository;

import com.ecommerce.oms.pricing.tax.domain.TaxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {

    Optional<TaxRate> findByCategoryId(Long categoryId);

    /** Fetches the whole ancestry's rates in one query so the upward walk costs one round trip. */
    List<TaxRate> findByCategoryIdIn(Collection<Long> categoryIds);
}
