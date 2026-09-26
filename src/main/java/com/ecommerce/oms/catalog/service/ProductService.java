package com.ecommerce.oms.catalog.service;

import com.ecommerce.oms.catalog.api.dto.CatalogDtos.*;
import com.ecommerce.oms.catalog.domain.Category;
import com.ecommerce.oms.catalog.domain.Product;
import com.ecommerce.oms.catalog.domain.ProductStatus;
import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.catalog.repository.ProductRepository;
import com.ecommerce.oms.catalog.repository.ProductVariantRepository;
import com.ecommerce.oms.catalog.repository.spec.ProductSpecifications;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Catalog reads for customers and catalog writes for admins.
 *
 * <p>Browse composes only the filters the caller supplied ({@link ProductSpecifications}),
 * then loads variants for the resulting page in a second query. Pagination and collection
 * fetching are kept apart on purpose: doing both in one statement forces Hibernate to
 * paginate in memory, which is correct on 12 seed products and wrong on 12,000.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final CategoryService categoryService;

    // ------------------------------------------------------------------ public browse

    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> browse(ProductSearchCriteria criteria, Pageable pageable,
                                               boolean includeNonActive) {
        Set<Long> categoryIds = resolveCategoryIds(criteria);

        Specification<Product> spec = Specification.allOf(
                ProductSpecifications.matchesText(criteria.q()),
                ProductSpecifications.inCategories(categoryIds),
                ProductSpecifications.hasBrand(criteria.brand()),
                ProductSpecifications.priceBetween(criteria.minPrice(), criteria.maxPrice()),
                includeNonActive ? null : ProductSpecifications.hasStatus(ProductStatus.ACTIVE));

        Page<Product> page = productRepository.findAll(spec, pageable);
        Map<Long, List<ProductVariant>> variantsByProduct = loadVariants(page.getContent());

        log.debug("Catalog browse: q={} categoryIds={} brand={} price=[{},{}] -> {} of {} product(s)",
                criteria.q(), categoryIds, criteria.brand(), criteria.minPrice(), criteria.maxPrice(),
                page.getNumberOfElements(), page.getTotalElements());

        return PageResponse.from(page, product ->
                ProductSummary.from(product, variantsByProduct.getOrDefault(product.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public ProductDetail findDetail(Long id, boolean includeNonActive) {
        Product product = productRepository.findWithVariantsById(id)
                .orElseThrow(() -> ApiException.notFound("Product", id));
        if (!includeNonActive && product.getStatus() != ProductStatus.ACTIVE) {
            // Hidden products are indistinguishable from missing ones to a customer.
            throw ApiException.notFound("Product", id);
        }
        return ProductDetail.from(product);
    }

    /** Shared loader for cart, pricing, and checkout: one query, product and category attached. */
    @Transactional(readOnly = true)
    public ProductVariant loadPurchasableVariant(Long variantId) {
        ProductVariant variant = variantRepository.findWithProductById(variantId)
                .orElseThrow(() -> ApiException.notFound("Product variant", variantId));
        if (!variant.isPurchasable()) {
            throw ApiException.businessRule(
                    "%s (%s) is not available for purchase".formatted(variant.getSku(), variant.getVariantName()));
        }
        return variant;
    }

    @Transactional(readOnly = true)
    public List<ProductVariant> loadVariants(Set<Long> variantIds) {
        return variantRepository.findAllWithProductByIdIn(variantIds);
    }

    // ------------------------------------------------------------------ admin writes

    @Transactional
    public ProductDetail create(ProductRequest request) {
        Category category = categoryService.load(request.categoryId());
        Product product = Product.create(request.name(), request.description(), request.brand(), category);
        if (request.status() != null) {
            product.changeStatus(request.status());
        }

        Optional.ofNullable(request.variants()).orElse(List.of())
                .forEach(variantRequest -> product.addVariant(buildVariant(variantRequest)));

        Product saved = productRepository.save(product);
        log.info("Product created: id={} name='{}' categoryId={} variants={}",
                saved.getId(), saved.getName(), category.getId(), saved.getVariants().size());
        return ProductDetail.from(saved);
    }

    @Transactional
    public ProductDetail update(Long id, ProductRequest request) {
        Product product = productRepository.findWithVariantsById(id)
                .orElseThrow(() -> ApiException.notFound("Product", id));
        Category category = categoryService.load(request.categoryId());

        product.update(request.name(), request.description(), request.brand(), category);
        if (request.status() != null) {
            product.changeStatus(request.status());
        }
        log.info("Product updated: id={} name='{}' status={}", id, product.getName(), product.getStatus());
        return ProductDetail.from(product);
    }

    @Transactional
    public VariantResponse addVariant(Long productId, VariantRequest request) {
        Product product = productRepository.findWithVariantsById(productId)
                .orElseThrow(() -> ApiException.notFound("Product", productId));
        ProductVariant variant = product.addVariant(buildVariant(request));
        productRepository.save(product);
        log.info("Variant added: productId={} sku={} price={}", productId, variant.getSku(), variant.getPrice());
        return VariantResponse.from(variant);
    }

    @Transactional
    public VariantResponse updateVariant(Long variantId, VariantRequest request) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> ApiException.notFound("Product variant", variantId));
        variant.update(request.variantName(), Money.of(request.price()),
                request.weightGrams() == null ? 0 : request.weightGrams(), true);
        log.info("Variant updated: id={} sku={} price={}", variantId, variant.getSku(), variant.getPrice());
        return VariantResponse.from(variant);
    }

    /**
     * Archives rather than deletes. An order line references the variant for returns and
     * reporting, so removing the row would break history that customers can still act on.
     */
    @Transactional
    public void archive(Long id) {
        Product product = productRepository.findWithVariantsById(id)
                .orElseThrow(() -> ApiException.notFound("Product", id));
        product.changeStatus(ProductStatus.ARCHIVED);
        product.getVariants().forEach(ProductVariant::deactivate);
        log.info("Product archived: id={} name='{}' (variants deactivated)", id, product.getName());
    }

    // ------------------------------------------------------------------ helpers

    private ProductVariant buildVariant(VariantRequest request) {
        if (variantRepository.existsBySkuIgnoreCase(request.sku())) {
            throw ApiException.duplicate("SKU already exists: " + request.sku());
        }
        return ProductVariant.create(request.sku(), request.variantName(), Money.of(request.price()),
                request.weightGrams() == null ? 0 : request.weightGrams());
    }

    private Set<Long> resolveCategoryIds(ProductSearchCriteria criteria) {
        if (criteria.categoryId() != null) {
            return categoryService.descendantIdsOf(criteria.categoryId());
        }
        if (criteria.categorySlug() != null && !criteria.categorySlug().isBlank()) {
            Category category = categoryService.loadBySlug(criteria.categorySlug());
            return categoryService.descendantIdsOf(category.getId());
        }
        return Set.of();
    }

    private Map<Long, List<ProductVariant>> loadVariants(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        Set<Long> productIds = products.stream().map(Product::getId).collect(Collectors.toSet());
        return variantRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));
    }
}
