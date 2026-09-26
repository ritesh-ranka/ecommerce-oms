package com.ecommerce.oms.catalog.repository.spec;

import com.ecommerce.oms.catalog.domain.Product;
import com.ecommerce.oms.catalog.domain.ProductStatus;
import com.ecommerce.oms.catalog.domain.ProductVariant;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * Composable catalog filters (Specification pattern).
 *
 * <p>Each method is one independent predicate; the service {@code and}s together only the
 * ones the caller actually supplied. That is what keeps a five-filter search endpoint from
 * needing 2^5 repository methods.
 */
public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    /** Free-text match across name, description, and brand. */
    public static Specification<Product> matchesText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String pattern = "%" + text.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("brand"), "")), pattern));
    }

    /**
     * Matches any category in the supplied set. The caller passes the target category plus
     * its descendants, so browsing "Electronics" also returns phones.
     */
    public static Specification<Product> inCategories(Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("category").get("id").in(categoryIds);
    }

    public static Specification<Product> hasStatus(ProductStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Product> hasBrand(String brand) {
        if (brand == null || brand.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(cb.lower(root.get("brand")), brand.trim().toLowerCase());
    }

    /**
     * Keeps products that have at least one active variant priced in range.
     *
     * <p>Implemented as an {@code EXISTS} subquery rather than a join, because a join would
     * multiply the product row per matching variant and corrupt both the page count and the
     * page contents.
     */
    public static Specification<Product> priceBetween(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            var variant = subquery.from(ProductVariant.class);
            subquery.select(variant.get("id"));

            var conditions = cb.conjunction();
            conditions = cb.and(conditions, cb.equal(variant.get("product").get("id"), root.get("id")));
            conditions = cb.and(conditions, cb.isTrue(variant.get("active")));
            if (min != null) {
                conditions = cb.and(conditions,
                        cb.greaterThanOrEqualTo(variant.get("price").get("amount"), min));
            }
            if (max != null) {
                conditions = cb.and(conditions,
                        cb.lessThanOrEqualTo(variant.get("price").get("amount"), max));
            }
            subquery.where(conditions);
            return cb.exists(subquery);
        };
    }

    /** Products with at least one purchasable variant. */
    public static Specification<Product> hasActiveVariant() {
        return (root, query, cb) -> {
            Join<Product, ProductVariant> variants = root.join("variants");
            query.distinct(true);
            return cb.isTrue(variants.get("active"));
        };
    }
}
