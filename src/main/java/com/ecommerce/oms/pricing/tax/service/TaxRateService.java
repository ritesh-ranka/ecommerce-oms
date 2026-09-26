package com.ecommerce.oms.pricing.tax.service;

import com.ecommerce.oms.catalog.service.CategoryService;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.pricing.api.dto.PricingDtos.TaxRateRequest;
import com.ecommerce.oms.pricing.api.dto.PricingDtos.TaxRateResponse;
import com.ecommerce.oms.pricing.tax.domain.TaxRate;
import com.ecommerce.oms.pricing.tax.repository.TaxRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Admin CRUD for tax rates. Resolution logic lives in {@link TaxCalculator}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxRateService {

    private final TaxRateRepository taxRateRepository;
    private final CategoryService categoryService;

    @Transactional(readOnly = true)
    public List<TaxRateResponse> findAll() {
        return taxRateRepository.findAll().stream().map(TaxRateResponse::from).toList();
    }

    /**
     * Sets the rate for a category, replacing any existing one.
     *
     * <p>Upsert rather than separate create/update endpoints: a category has at most one rate (the
     * table enforces it), so "create" and "update" are the same intent expressed twice.
     */
    @Transactional
    public TaxRateResponse upsert(TaxRateRequest request) {
        categoryService.load(request.categoryId());

        TaxRate taxRate = taxRateRepository.findByCategoryId(request.categoryId())
                .map(existing -> {
                    existing.update(request.rate(), request.description());
                    log.info("Tax rate updated: categoryId={} rate={}", request.categoryId(), request.rate());
                    return existing;
                })
                .orElseGet(() -> {
                    log.info("Tax rate created: categoryId={} rate={}", request.categoryId(), request.rate());
                    return taxRateRepository.save(
                            TaxRate.of(request.categoryId(), request.rate(), request.description()));
                });

        return TaxRateResponse.from(taxRate);
    }

    /**
     * Removes a rate, after which the category inherits from its nearest ancestor with one, or
     * falls back to the configured default. Deleting a rate therefore never leaves a category
     * untaxable.
     */
    @Transactional
    public void delete(Long id) {
        TaxRate taxRate = taxRateRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Tax rate", id));
        taxRateRepository.delete(taxRate);
        log.info("Tax rate deleted: id={} categoryId={} — category now inherits from its ancestry",
                id, taxRate.getCategoryId());
    }
}
