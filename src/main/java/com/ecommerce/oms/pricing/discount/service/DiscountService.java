package com.ecommerce.oms.pricing.discount.service;

import com.ecommerce.oms.catalog.service.CategoryService;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.error.ErrorCode;
import com.ecommerce.oms.pricing.api.dto.PricingDtos.DiscountRequest;
import com.ecommerce.oms.pricing.api.dto.PricingDtos.DiscountResponse;
import com.ecommerce.oms.pricing.discount.domain.Discount;
import com.ecommerce.oms.pricing.discount.domain.DiscountRedemption;
import com.ecommerce.oms.pricing.discount.repository.DiscountRedemptionRepository;
import com.ecommerce.oms.pricing.discount.repository.DiscountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Coupon lifecycle: validation, scope resolution, redemption, and release.
 *
 * <p>Validation is split across three places, each holding the part it can actually answer:
 * {@link Discount} knows its own window and global cap; this service knows the buyer's history and
 * the category scope; {@code DiscountStage} knows the basket. Collapsing them would force one of
 * the three to reach for data it has no business loading.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscountService {

    private final DiscountRepository discountRepository;
    private final DiscountRedemptionRepository redemptionRepository;
    private final CategoryService categoryService;

    // ------------------------------------------------------------------ validation

    /**
     * Resolves a coupon code for a specific customer, or explains why it cannot be used.
     *
     * <p>Every rejection is a 422 with a reason the customer can act on. Returning a silent
     * "no discount applied" instead would leave someone staring at a total that does not match
     * the promotion they were shown.
     */
    @Transactional(readOnly = true)
    public Discount validateForCustomer(String code, Long customerId) {
        Discount discount = discountRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> {
                    log.info("Coupon rejected: unknown code={}", code);
                    return ApiException.of(ErrorCode.DISCOUNT_NOT_APPLICABLE,
                            "Coupon %s is not a valid code".formatted(code));
                });

        Instant now = Instant.now();
        String rejection = discount.rejectionReason(now);
        if (rejection != null) {
            log.info("Coupon rejected: code={} reason={}", code, rejection);
            throw ApiException.of(ErrorCode.DISCOUNT_NOT_APPLICABLE, rejection);
        }

        if (customerId != null && discount.getPerCustomerLimit() != null) {
            long alreadyUsed = redemptionRepository.countByDiscountIdAndUserId(discount.getId(), customerId);
            if (alreadyUsed >= discount.getPerCustomerLimit()) {
                log.info("Coupon rejected: code={} userId={} already used {} of {} allowed",
                        code, customerId, alreadyUsed, discount.getPerCustomerLimit());
                throw ApiException.of(ErrorCode.DISCOUNT_NOT_APPLICABLE,
                        "You have already used coupon %s the maximum number of times".formatted(code));
            }
        }

        log.debug("Coupon {} validated for userId={}", code, customerId);
        return discount;
    }

    /**
     * Category ids a scoped coupon may discount: the configured category plus its descendants.
     *
     * <p>Including descendants is what makes "15% off Audio" work when the products are actually
     * filed under sub-categories of Audio — the alternative surprises both the merchandiser who
     * configured it and the customer who was promised it.
     */
    @Transactional(readOnly = true)
    public Set<Long> eligibleCategoryIds(Discount discount) {
        if (!discount.isScopedToCategory()) {
            return Set.of();
        }
        return categoryService.descendantIdsOf(discount.getCategoryId());
    }

    // ------------------------------------------------------------------ redemption

    /**
     * Consumes one use of the coupon and records who used it.
     *
     * <p>Runs inside the checkout transaction after payment is captured, under a row lock, so the
     * global cap holds under concurrency. Recording it before payment would burn redemptions on
     * orders that were never paid for.
     */
    @Transactional
    public void recordRedemption(Long discountId, Long userId, Long orderId, Money saved) {
        Discount discount = discountRepository.lockById(discountId)
                .orElseThrow(() -> ApiException.notFound("Discount", discountId));

        if (!discount.hasRemainingGlobalUses()) {
            log.warn("Coupon {} exhausted between pricing and capture for orderId={}",
                    discount.getCode(), orderId);
            throw ApiException.of(ErrorCode.DISCOUNT_NOT_APPLICABLE,
                    "Coupon %s was fully redeemed while your order was being processed"
                            .formatted(discount.getCode()));
        }

        discount.recordRedemption();
        discountRepository.save(discount);
        redemptionRepository.save(DiscountRedemption.of(discount, userId, orderId, saved));

        log.info("Coupon redeemed: code={} orderId={} userId={} saved={} (now {} of {} uses)",
                discount.getCode(), orderId, userId, saved, discount.getTimesRedeemed(),
                discount.getUsageLimit() == null ? "unlimited" : discount.getUsageLimit());
    }

    /**
     * Gives the redemption back when an order is cancelled or fully returned.
     *
     * <p>Without this, a customer who cancels loses the coupon and a limited promotion silently
     * burns down its cap on orders that never completed.
     */
    @Transactional
    public void releaseRedemption(Long orderId) {
        Optional<DiscountRedemption> redemption = redemptionRepository.findByOrderId(orderId);
        if (redemption.isEmpty()) {
            return;
        }

        DiscountRedemption record = redemption.get();
        Discount discount = discountRepository.lockById(record.getDiscount().getId())
                .orElseThrow(() -> ApiException.notFound("Discount", record.getDiscount().getId()));

        discount.releaseRedemption();
        discountRepository.save(discount);
        redemptionRepository.delete(record);

        log.info("Coupon redemption released: code={} orderId={} (now {} uses)",
                discount.getCode(), orderId, discount.getTimesRedeemed());
    }

    // ------------------------------------------------------------------ admin CRUD

    @Transactional(readOnly = true)
    public List<DiscountResponse> findAll() {
        return discountRepository.findAllByOrderByCodeAsc().stream().map(DiscountResponse::from).toList();
    }

    @Transactional
    public DiscountResponse create(DiscountRequest request) {
        if (discountRepository.existsByCodeIgnoreCase(request.code())) {
            throw ApiException.duplicate("A coupon already exists with code " + request.code());
        }
        if (request.categoryId() != null) {
            categoryService.load(request.categoryId());
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw ApiException.validation("endsAt must be after startsAt");
        }

        Discount saved = discountRepository.save(Discount.create(
                request.code(), request.description(), request.type(), request.discountValue(),
                request.maxDiscount() == null ? null : Money.of(request.maxDiscount()),
                request.minOrderValue() == null ? null : Money.of(request.minOrderValue()),
                request.categoryId(), request.startsAt(), request.endsAt(),
                request.usageLimit(), request.perCustomerLimit()));

        log.info("Coupon created: code={} type={} value={} scopedToCategory={}",
                saved.getCode(), saved.getType(), saved.getDiscountValue(), saved.getCategoryId());
        return DiscountResponse.from(saved);
    }

    @Transactional
    public DiscountResponse update(Long id, DiscountRequest request) {
        Discount discount = discountRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Discount", id));
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw ApiException.validation("endsAt must be after startsAt");
        }

        discount.update(request.description(), request.discountValue(),
                request.maxDiscount() == null ? null : Money.of(request.maxDiscount()),
                request.minOrderValue() == null ? null : Money.of(request.minOrderValue()),
                request.categoryId(), request.startsAt(), request.endsAt(),
                request.usageLimit(), request.perCustomerLimit(),
                request.active() == null || request.active());

        log.info("Coupon updated: code={} active={}", discount.getCode(), discount.isActive());
        return DiscountResponse.from(discount);
    }

    /**
     * Deactivates rather than deletes. Historical orders reference the redemption, and finance
     * needs to know what a past campaign cost.
     */
    @Transactional
    public void deactivate(Long id) {
        Discount discount = discountRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Discount", id));
        discount.update(discount.getDescription(), discount.getDiscountValue(), discount.getMaxDiscount(),
                discount.getMinOrderValue(), discount.getCategoryId(), discount.getStartsAt(),
                discount.getEndsAt(), discount.getUsageLimit(), discount.getPerCustomerLimit(), false);
        log.info("Coupon deactivated: code={}", discount.getCode());
    }
}
