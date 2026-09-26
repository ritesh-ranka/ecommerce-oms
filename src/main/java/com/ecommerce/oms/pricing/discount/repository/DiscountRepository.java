package com.ecommerce.oms.pricing.discount.repository;

import com.ecommerce.oms.pricing.discount.domain.Discount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiscountRepository extends JpaRepository<Discount, Long> {

    Optional<Discount> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<Discount> findAllByOrderByCodeAsc();

    /**
     * Locks the coupon row before incrementing {@code timesRedeemed}.
     *
     * <p>Needed for the global usage cap: two customers racing for the last redemption of a
     * limited coupon would otherwise both read {@code timesRedeemed = 99} and both succeed. The
     * {@code @Version} column would catch it as a lost update, but locking turns a retry into a
     * short wait, which is the better outcome inside a checkout.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Discount d where d.id = :id")
    Optional<Discount> lockById(@Param("id") Long id);
}
