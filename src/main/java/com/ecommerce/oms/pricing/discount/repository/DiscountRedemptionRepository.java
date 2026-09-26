package com.ecommerce.oms.pricing.discount.repository;

import com.ecommerce.oms.pricing.discount.domain.DiscountRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiscountRedemptionRepository extends JpaRepository<DiscountRedemption, Long> {

    /** Backs the per-customer usage cap, which the global counter cannot express. */
    long countByDiscountIdAndUserId(Long discountId, Long userId);

    Optional<DiscountRedemption> findByOrderId(Long orderId);

    void deleteByOrderId(Long orderId);
}
