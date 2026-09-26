package com.ecommerce.oms.catalog.api;

import com.ecommerce.oms.catalog.api.dto.CatalogDtos.*;
import com.ecommerce.oms.catalog.service.CategoryService;
import com.ecommerce.oms.catalog.service.ProductService;
import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public catalog browse. Deliberately unauthenticated: a storefront must be crawlable and
 * shoppable before anyone signs in, and nothing here is customer-specific.
 */
@Tag(name = "2. Catalog (public)", description = "Browse categories, products, and variants")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirements
public class CatalogController {

    private final ProductService productService;
    private final CategoryService categoryService;

    @GetMapping("/categories")
    @Operation(summary = "Full category tree",
            description = "Nested response. Products are filterable by any node; descendants are included.")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> categories() {
        return ResponseEntity.ok(ApiResponse.ok(categoryService.fullTree()));
    }

    @GetMapping("/categories/{id}")
    @Operation(summary = "Single category")
    public ResponseEntity<ApiResponse<CategoryResponse>> category(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(categoryService.findById(id)));
    }

    @GetMapping("/products")
    @Operation(summary = "Search and filter products",
            description = """
                    All filters are optional and compose. Filtering by a parent category
                    includes its descendants, so `categorySlug=apparel` also returns items
                    filed under Men and Women.
                    """)
    public ResponseEntity<ApiResponse<PageResponse<ProductSummary>>> products(
            @Parameter(description = "Free text across name, description, brand") @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @Parameter(example = "audio") @RequestParam(required = false) String categorySlug,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {

        ProductSearchCriteria criteria =
                new ProductSearchCriteria(q, categoryId, categorySlug, brand, minPrice, maxPrice);
        return ResponseEntity.ok(ApiResponse.ok(productService.browse(criteria, pageable, false)));
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Product detail with all purchasable variants",
            description = "Variant ids returned here are what `POST /cart/items` expects.")
    public ResponseEntity<ApiResponse<ProductDetail>> product(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(productService.findDetail(id, false)));
    }
}
