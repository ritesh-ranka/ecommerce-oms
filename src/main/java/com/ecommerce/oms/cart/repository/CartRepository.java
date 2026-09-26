package com.ecommerce.oms.cart.repository;

import com.ecommerce.oms.cart.domain.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Loads the cart with its lines, variants, products, and categories in one query.
     *
     * <p>The deep fetch is not gratuitous: the pricing engine needs each line's price (variant),
     * display name (product), and tax category (category ancestry). Without it, reading a
     * ten-line cart issues thirty extra queries.
     */
    @Query("""
            select distinct c from Cart c
            left join fetch c.items i
            left join fetch i.variant v
            left join fetch v.product p
            left join fetch p.category
            where c.userId = :userId
            """)
    Optional<Cart> findByUserIdWithItems(@Param("userId") Long userId);

    Optional<Cart> findByUserId(Long userId);
}
