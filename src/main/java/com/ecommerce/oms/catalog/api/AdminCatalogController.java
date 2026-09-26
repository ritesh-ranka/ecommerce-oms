package com.ecommerce.oms.catalog.api;

import com.ecommerce.oms.catalog.api.dto.CatalogDtos.*;
import com.ecommerce.oms.catalog.service.CategoryService;
import com.ecommerce.oms.catalog.service.ProductService;
import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Catalog management. Every method is gated on {@code ROLE_ADMIN} at the edge; there is no
 * per-row ownership concept for catalog data, so the role check is the complete rule here.
 */
@Tag(name = "8. Admin — Catalog", description = "Category and product management (ADMIN)")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class AdminCatalogController {

    private final ProductService productService;
    private final CategoryService categoryService;

    // ------------------------------------------------------------------ categories

    @PostMapping("/categories")
    @Operation(summary = "Create a category")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(categoryService.create(request), "Category created"));
    }

    @PutMapping("/categories/{id}")
    @Operation(summary = "Update or re-parent a category",
            description = "Rejects a move that would place a category under its own descendant.")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(categoryService.update(id, request), "Category updated"));
    }

    @DeleteMapping("/categories/{id}")
    @Operation(summary = "Delete an empty category",
            description = "Refused with 422 if the category still has children or products.")
    public ResponseEntity<ApiResponse<Void>> deleteCategory(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.ok(ApiResponse.message("Category deleted"));
    }

    // ------------------------------------------------------------------ products

    @GetMapping("/products")
    @Operation(summary = "List products including DRAFT and ARCHIVED",
            description = "Same filters as the public endpoint, minus the ACTIVE-only restriction.")
    public ResponseEntity<ApiResponse<PageResponse<ProductSummary>>> listAll(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        ProductSearchCriteria criteria =
                new ProductSearchCriteria(q, categoryId, null, brand, minPrice, maxPrice);
        return ResponseEntity.ok(ApiResponse.ok(productService.browse(criteria, pageable, true)));
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Product detail regardless of status")
    public ResponseEntity<ApiResponse<ProductDetail>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(productService.findDetail(id, true)));
    }

    @PostMapping("/products")
    @Operation(summary = "Create a product with its initial variants")
    public ResponseEntity<ApiResponse<ProductDetail>> createProduct(
            @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(productService.create(request), "Product created"));
    }

    @PutMapping("/products/{id}")
    @Operation(summary = "Update product attributes and status")
    public ResponseEntity<ApiResponse<ProductDetail>> updateProduct(
            @PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(productService.update(id, request), "Product updated"));
    }

    @DeleteMapping("/products/{id}")
    @Operation(summary = "Archive a product",
            description = """
                    Soft delete. Order lines snapshot name and SKU but still reference the
                    variant for returns and reporting, so the row is never removed.
                    """)
    public ResponseEntity<ApiResponse<Void>> archiveProduct(@PathVariable Long id) {
        productService.archive(id);
        return ResponseEntity.ok(ApiResponse.message("Product archived and variants deactivated"));
    }

    // ------------------------------------------------------------------ variants

    @PostMapping("/products/{productId}/variants")
    @Operation(summary = "Add a variant (SKU) to an existing product")
    public ResponseEntity<ApiResponse<VariantResponse>> addVariant(
            @PathVariable Long productId, @Valid @RequestBody VariantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(productService.addVariant(productId, request), "Variant added"));
    }

    @PutMapping("/variants/{variantId}")
    @Operation(summary = "Update a variant's name, price, or weight")
    public ResponseEntity<ApiResponse<VariantResponse>> updateVariant(
            @PathVariable Long variantId, @Valid @RequestBody VariantRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(productService.updateVariant(variantId, request), "Variant updated"));
    }
}
