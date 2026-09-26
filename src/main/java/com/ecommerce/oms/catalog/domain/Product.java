package com.ecommerce.oms.catalog.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A sellable concept ("Everyday Cotton Tee"). Stock and price live on its
 * {@link ProductVariant}s, never here — a product is not something you can hold.
 */
@Entity
@Getter
@Table(name = "products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

    @Column(name = "name", nullable = false, length = 190)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "brand", length = 90)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ProductStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductVariant> variants = new ArrayList<>();

    public static Product create(String name, String description, String brand, Category category) {
        Product product = new Product();
        product.name = name.trim();
        product.description = description;
        product.brand = brand;
        product.category = category;
        product.status = ProductStatus.ACTIVE;
        return product;
    }

    public void update(String name, String description, String brand, Category category) {
        this.name = name.trim();
        this.description = description;
        this.brand = brand;
        this.category = category;
    }

    public void changeStatus(ProductStatus status) {
        this.status = status;
    }

    /** Keeps both sides of the association consistent; callers never touch the list. */
    public ProductVariant addVariant(ProductVariant variant) {
        variant.attachTo(this);
        this.variants.add(variant);
        return variant;
    }

    public boolean isPurchasable() {
        return status.isPurchasable();
    }
}
