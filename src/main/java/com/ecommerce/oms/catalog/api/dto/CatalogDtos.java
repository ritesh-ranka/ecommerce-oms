package com.ecommerce.oms.catalog.api.dto;

import com.ecommerce.oms.catalog.domain.Category;
import com.ecommerce.oms.catalog.domain.Product;
import com.ecommerce.oms.catalog.domain.ProductStatus;
import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.common.domain.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Catalog request and response payloads.
 *
 * <p>Responses are explicit DTOs rather than serialised entities. Returning entities would
 * leak the persistence model onto the wire, drag lazy associations into the JSON, and make
 * every schema change a breaking API change.
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    // ------------------------------------------------------------------ categories

    @Schema(name = "CategoryRequest")
    public record CategoryRequest(
            @NotBlank @Size(max = 120) @Schema(example = "Wearables") String name,
            @NotBlank @Size(max = 140)
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Slug may contain lowercase letters, digits, and hyphens only")
            @Schema(example = "wearables") String slug,
            @Schema(description = "Parent category id, omit for a root category", example = "1")
            Long parentId
    ) {
    }

    @Schema(name = "CategoryResponse")
    public record CategoryResponse(
            Long id,
            String name,
            String slug,
            Long parentId,
            String parentName,
            List<CategoryResponse> children
    ) {
        public static CategoryResponse flat(Category category) {
            return new CategoryResponse(
                    category.getId(), category.getName(), category.getSlug(),
                    category.getParent() == null ? null : category.getParent().getId(),
                    category.getParent() == null ? null : category.getParent().getName(),
                    null);
        }

        public static CategoryResponse tree(Category category, List<CategoryResponse> children) {
            return new CategoryResponse(
                    category.getId(), category.getName(), category.getSlug(),
                    category.getParent() == null ? null : category.getParent().getId(),
                    category.getParent() == null ? null : category.getParent().getName(),
                    children);
        }
    }

    // ------------------------------------------------------------------ products

    @Schema(name = "ProductRequest")
    public record ProductRequest(
            @NotBlank @Size(max = 190) @Schema(example = "Aurora X7 Smartphone") String name,
            @Size(max = 2000) @Schema(example = "6.7-inch OLED, 5200mAh battery.") String description,
            @Size(max = 90) @Schema(example = "Aurora") String brand,
            @NotNull @Positive @Schema(example = "2") Long categoryId,
            @Schema(description = "Defaults to ACTIVE when omitted") ProductStatus status,
            @Valid @Size(max = 50) List<VariantRequest> variants
    ) {
    }

    @Schema(name = "VariantRequest")
    public record VariantRequest(
            @NotBlank @Size(max = 60) @Schema(example = "AUR-X7-256-BLU") String sku,
            @NotBlank @Size(max = 120) @Schema(example = "256GB / Blue") String variantName,
            @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2)
            @Schema(description = "Tax-exclusive list price", example = "34999.00") BigDecimal price,
            @PositiveOrZero @Schema(example = "215") Integer weightGrams
    ) {
    }

    @Schema(name = "VariantResponse")
    public record VariantResponse(
            Long id,
            String sku,
            String variantName,
            Money price,
            int weightGrams,
            boolean active
    ) {
        public static VariantResponse from(ProductVariant variant) {
            return new VariantResponse(variant.getId(), variant.getSku(), variant.getVariantName(),
                    variant.getPrice(), variant.getWeightGrams(), variant.isActive());
        }
    }

    /** Browse row: the price range is what a listing page actually needs. */
    @Schema(name = "ProductSummary")
    public record ProductSummary(
            Long id,
            String name,
            String brand,
            ProductStatus status,
            Long categoryId,
            String categoryName,
            Money minPrice,
            Money maxPrice,
            int variantCount
    ) {
        public static ProductSummary from(Product product, List<ProductVariant> variants) {
            Money min = variants.stream().map(ProductVariant::getPrice)
                    .min(Comparator.naturalOrder()).orElse(Money.zero());
            Money max = variants.stream().map(ProductVariant::getPrice)
                    .max(Comparator.naturalOrder()).orElse(Money.zero());
            return new ProductSummary(product.getId(), product.getName(), product.getBrand(),
                    product.getStatus(), product.getCategory().getId(), product.getCategory().getName(),
                    min, max, variants.size());
        }
    }

    @Schema(name = "ProductDetail")
    public record ProductDetail(
            Long id,
            String name,
            String description,
            String brand,
            ProductStatus status,
            Long categoryId,
            String categoryName,
            List<VariantResponse> variants
    ) {
        public static ProductDetail from(Product product) {
            return new ProductDetail(product.getId(), product.getName(), product.getDescription(),
                    product.getBrand(), product.getStatus(),
                    product.getCategory().getId(), product.getCategory().getName(),
                    product.getVariants().stream().map(VariantResponse::from).toList());
        }
    }

    /** Query parameters for catalog browse, bound as a single object. */
    @Schema(name = "ProductSearchCriteria")
    public record ProductSearchCriteria(
            @Schema(description = "Free-text across name, description, brand") String q,
            @Schema(description = "Category id; descendants are included") Long categoryId,
            @Schema(description = "Category slug; alternative to categoryId") String categorySlug,
            String brand,
            @DecimalMin("0.00") BigDecimal minPrice,
            @DecimalMin("0.00") BigDecimal maxPrice
    ) {
    }
}
