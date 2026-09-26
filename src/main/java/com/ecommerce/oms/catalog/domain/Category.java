package com.ecommerce.oms.catalog.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the category tree. Self-referencing parent link, arbitrary depth.
 *
 * <p>The tree is not decoration: tax rate resolution walks it upward until it finds a
 * configured rate, so "Mobiles" inherits 18% from "Electronics" without duplicating the
 * row. Category-scoped discounts use the same walk.
 */
@Entity
@Getter
@Table(name = "categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** URL-safe identifier, unique across the tree. */
    @Column(name = "slug", nullable = false, length = 140, unique = true)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent")
    private List<Category> children = new ArrayList<>();

    public static Category create(String name, String slug, Category parent) {
        Category category = new Category();
        category.name = name.trim();
        category.slug = slug.trim().toLowerCase();
        category.parent = parent;
        return category;
    }

    public void rename(String name, String slug) {
        this.name = name.trim();
        this.slug = slug.trim().toLowerCase();
    }

    public void reparent(Category parent) {
        if (parent != null && parent.getId() != null && parent.getId().equals(this.getId())) {
            throw new IllegalArgumentException("A category cannot be its own parent");
        }
        this.parent = parent;
    }

    public boolean isRoot() {
        return parent == null;
    }
}
