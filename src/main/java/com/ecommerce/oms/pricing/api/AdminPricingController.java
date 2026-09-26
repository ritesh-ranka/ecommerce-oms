package com.ecommerce.oms.pricing.api;

import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.pricing.api.dto.PricingDtos.*;
import com.ecommerce.oms.pricing.discount.service.DiscountService;
import com.ecommerce.oms.pricing.tax.service.TaxRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Pricing configuration: coupons and tax rates.
 *
 * <p>Admin-only. Customers <em>apply</em> a coupon by passing its code to preview or checkout; they
 * can never enumerate the coupon table, which would otherwise hand out every unadvertised
 * promotion in the system.
 */
@Tag(name = "10. Admin — Pricing", description = "Discount and tax rate configuration (ADMIN)")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class AdminPricingController {

    private final DiscountService discountService;
    private final TaxRateService taxRateService;

    // ------------------------------------------------------------------ discounts

    @GetMapping("/discounts")
    @Operation(summary = "List every coupon with its redemption counters")
    public ResponseEntity<ApiResponse<List<DiscountResponse>>> listDiscounts() {
        return ResponseEntity.ok(ApiResponse.ok(discountService.findAll()));
    }

    @PostMapping("/discounts")
    @Operation(summary = "Create a coupon",
            description = """
                    Supports percentage-off (optionally capped) and flat-amount-off, each with an
                    optional minimum order value, category scope, validity window, global usage cap,
                    and per-customer cap.

                    One coupon per order — stacking is explicitly out of scope.
                    """)
    public ResponseEntity<ApiResponse<DiscountResponse>> createDiscount(
            @Valid @RequestBody DiscountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(discountService.create(request), "Coupon created"));
    }

    @PutMapping("/discounts/{id}")
    @Operation(summary = "Update a coupon's terms or toggle it")
    public ResponseEntity<ApiResponse<DiscountResponse>> updateDiscount(
            @PathVariable Long id, @Valid @RequestBody DiscountRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(discountService.update(id, request), "Coupon updated"));
    }

    @DeleteMapping("/discounts/{id}")
    @Operation(summary = "Deactivate a coupon",
            description = "Soft delete: past redemptions are kept so a campaign's cost stays auditable.")
    public ResponseEntity<ApiResponse<Void>> deactivateDiscount(@PathVariable Long id) {
        discountService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.message("Coupon deactivated"));
    }

    // ------------------------------------------------------------------ tax rates

    @GetMapping("/tax-rates")
    @Operation(summary = "List configured tax rates",
            description = """
                    Rates are sparse by design. A category with no row inherits from its nearest
                    ancestor that has one, and failing that from `oms.pricing.default-tax-rate`.
                    """)
    public ResponseEntity<ApiResponse<List<TaxRateResponse>>> listTaxRates() {
        return ResponseEntity.ok(ApiResponse.ok(taxRateService.findAll()));
    }

    @PutMapping("/tax-rates")
    @Operation(summary = "Set the tax rate for a category",
            description = "Upsert: a category has at most one rate. Rate is a fraction (0.18 = 18%).")
    public ResponseEntity<ApiResponse<TaxRateResponse>> upsertTaxRate(
            @Valid @RequestBody TaxRateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(taxRateService.upsert(request), "Tax rate saved"));
    }

    @DeleteMapping("/tax-rates/{id}")
    @Operation(summary = "Remove a tax rate",
            description = "The category then inherits from its ancestry, so it is never left untaxable.")
    public ResponseEntity<ApiResponse<Void>> deleteTaxRate(@PathVariable Long id) {
        taxRateService.delete(id);
        return ResponseEntity.ok(ApiResponse.message("Tax rate deleted"));
    }
}
