package com.ecommerce.oms.pricing.tax.service;

import com.ecommerce.oms.catalog.domain.Category;
import com.ecommerce.oms.catalog.service.CategoryService;
import com.ecommerce.oms.common.config.OmsProperties;
import com.ecommerce.oms.pricing.tax.domain.TaxRate;
import com.ecommerce.oms.pricing.tax.repository.TaxRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the tax rate for a category by walking the category tree upward.
 *
 * <h2>Why inherit rather than require a rate per category</h2>
 * A catalog with sixty leaf categories would otherwise need sixty tax rows, all saying 18%, and a
 * rate change would mean sixty edits with no guarantee they stay consistent. Inheritance means the
 * rate is configured once at the level where the tax authority actually draws the line, and a new
 * sub-category is correctly taxed the moment it is created — with no chance of someone forgetting
 * to add its row.
 *
 * <h2>Resolution order</h2>
 * Nearest ancestor wins, so a specific override beats a general rule:
 * {@code Mobiles -> Electronics (0.18) -> default}. If nothing in the chain has a rate, the
 * configured {@code oms.pricing.default-tax-rate} applies, which guarantees this method always
 * returns a usable number rather than failing a checkout over missing configuration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxCalculator {

    private final TaxRateRepository taxRateRepository;
    private final CategoryService categoryService;
    private final OmsProperties properties;

    /**
     * Tax fraction for a category, nearest configured ancestor first.
     *
     * <p>Fetches every rate in the ancestry in one query rather than querying per level: a
     * five-deep category would otherwise cost five round trips per line.
     */
    @Transactional(readOnly = true)
    public BigDecimal rateFor(Long categoryId) {
        if (categoryId == null) {
            return defaultRate();
        }

        List<Category> ancestry = categoryService.ancestryOf(categoryId);
        List<Long> ancestryIds = ancestry.stream().map(Category::getId).toList();

        Map<Long, BigDecimal> ratesByCategory = taxRateRepository.findByCategoryIdIn(ancestryIds).stream()
                .collect(Collectors.toMap(TaxRate::getCategoryId, TaxRate::getRate));

        // ancestryOf returns nearest-first, so the first hit is the most specific rule.
        for (Category category : ancestry) {
            BigDecimal rate = ratesByCategory.get(category.getId());
            if (rate != null) {
                log.trace("Tax rate for categoryId={} resolved to {} from '{}'",
                        categoryId, rate, category.getName());
                return rate;
            }
        }

        log.debug("No tax rate configured anywhere in the ancestry of categoryId={}; using default {}",
                categoryId, defaultRate());
        return defaultRate();
    }

    /** Resolver handed to the pricing context so repeated lookups within one basket are cached. */
    public Function<Long, BigDecimal> resolver() {
        return this::rateFor;
    }

    private BigDecimal defaultRate() {
        return properties.pricing().defaultTaxRate();
    }
}
