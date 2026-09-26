package com.ecommerce.oms.catalog.service;

import com.ecommerce.oms.catalog.api.dto.CatalogDtos.CategoryRequest;
import com.ecommerce.oms.catalog.api.dto.CatalogDtos.CategoryResponse;
import com.ecommerce.oms.catalog.domain.Category;
import com.ecommerce.oms.catalog.repository.CategoryRepository;
import com.ecommerce.oms.catalog.repository.ProductRepository;
import com.ecommerce.oms.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Category tree management and traversal.
 *
 * <p>The two traversal helpers are the reason this service exists beyond CRUD:
 * {@link #descendantIdsOf(Long)} powers "browsing a parent shows children's products", and
 * {@link #ancestryOf(Long)} powers tax-rate and category-scoped-discount resolution. Both
 * walk an in-memory map because the tree is small and bounded; a recursive CTE would be the
 * next step if it were not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<CategoryResponse> fullTree() {
        List<Category> all = categoryRepository.findAll();
        Map<Long, List<Category>> childrenByParent = new HashMap<>();
        all.forEach(category -> {
            Long parentId = category.getParent() == null ? null : category.getParent().getId();
            childrenByParent.computeIfAbsent(parentId, key -> new ArrayList<>()).add(category);
        });
        return buildLevel(childrenByParent, null);
    }

    @Transactional(readOnly = true)
    public CategoryResponse findById(Long id) {
        return CategoryResponse.flat(load(id));
    }

    @Transactional(readOnly = true)
    public Category load(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Category", id));
    }

    @Transactional(readOnly = true)
    public Category loadBySlug(String slug) {
        return categoryRepository.findBySlugIgnoreCase(slug)
                .orElseThrow(() -> ApiException.notFound("Category with slug " + slug));
    }

    /**
     * The category plus every category beneath it. Used by catalog browse so that filtering
     * on "Apparel" includes "Men" and "Women".
     */
    @Transactional(readOnly = true)
    public Set<Long> descendantIdsOf(Long categoryId) {
        Map<Long, List<Long>> childIds = new HashMap<>();
        categoryRepository.findAll().forEach(category -> {
            if (category.getParent() != null) {
                childIds.computeIfAbsent(category.getParent().getId(), key -> new ArrayList<>())
                        .add(category.getId());
            }
        });

        Set<Long> collected = new LinkedHashSet<>();
        Deque<Long> pending = new ArrayDeque<>(List.of(categoryId));
        while (!pending.isEmpty()) {
            Long current = pending.pop();
            if (collected.add(current)) {
                pending.addAll(childIds.getOrDefault(current, List.of()));
            }
        }
        return collected;
    }

    /**
     * The category and its ancestors, nearest first. Tax resolution consumes this list in
     * order and stops at the first configured rate, which is what makes a rate on
     * "Electronics" apply to "Mobiles" without a duplicate row.
     */
    @Transactional(readOnly = true)
    public List<Category> ancestryOf(Long categoryId) {
        List<Category> chain = new ArrayList<>();
        Category cursor = load(categoryId);
        Set<Long> guard = new HashSet<>();
        while (cursor != null && guard.add(cursor.getId())) {
            chain.add(cursor);
            cursor = cursor.getParent();
        }
        return chain;
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        if (categoryRepository.existsBySlugIgnoreCase(request.slug())) {
            throw ApiException.duplicate("A category already uses the slug " + request.slug());
        }
        Category parent = request.parentId() == null ? null : load(request.parentId());
        Category saved = categoryRepository.save(Category.create(request.name(), request.slug(), parent));
        log.info("Category created: id={} slug={} parentId={}", saved.getId(), saved.getSlug(),
                request.parentId());
        return CategoryResponse.flat(saved);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = load(id);
        categoryRepository.findBySlugIgnoreCase(request.slug())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.duplicate("A category already uses the slug " + request.slug());
                });

        category.rename(request.name(), request.slug());
        if (request.parentId() != null) {
            assertNotOwnDescendant(id, request.parentId());
            category.reparent(load(request.parentId()));
        } else {
            category.reparent(null);
        }
        log.info("Category updated: id={} slug={}", id, category.getSlug());
        return CategoryResponse.flat(category);
    }

    /**
     * Refuses to delete a category that still has children or products. Cascading the
     * delete would silently orphan catalog rows; an explicit 422 tells the admin what to
     * fix.
     */
    @Transactional
    public void delete(Long id) {
        Category category = load(id);
        if (categoryRepository.existsByParentId(id)) {
            throw ApiException.businessRule("Category has child categories; delete or move them first");
        }
        if (productRepository.existsByCategoryId(id)) {
            throw ApiException.businessRule("Category still has products; move them to another category first");
        }
        categoryRepository.delete(category);
        log.info("Category deleted: id={} slug={}", id, category.getSlug());
    }

    // ------------------------------------------------------------------ helpers

    private List<CategoryResponse> buildLevel(Map<Long, List<Category>> childrenByParent, Long parentId) {
        return childrenByParent.getOrDefault(parentId, List.of()).stream()
                .sorted(Comparator.comparing(Category::getName))
                .map(category -> CategoryResponse.tree(category,
                        buildLevel(childrenByParent, category.getId())))
                .toList();
    }

    /** Re-parenting a node under its own descendant would create a cycle. */
    private void assertNotOwnDescendant(Long categoryId, Long proposedParentId) {
        if (categoryId.equals(proposedParentId)) {
            throw ApiException.businessRule("A category cannot be its own parent");
        }
        if (descendantIdsOf(categoryId).contains(proposedParentId)) {
            throw ApiException.businessRule("Cannot move a category under one of its own descendants");
        }
    }
}
