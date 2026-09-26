package com.ecommerce.oms.pricing.discount.rule;

import com.ecommerce.oms.pricing.discount.domain.DiscountType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Maps {@link DiscountType} to its {@link DiscountRule} (Registry pattern).
 *
 * <p>Spring injects every rule bean, so supporting a new discount type — buy-one-get-one, free
 * shipping, tiered spend — is one new class and one new enum constant. Nothing here is edited,
 * and no {@code switch} accumulates a branch per promotion the marketing team invents.
 *
 * <p>Startup fails if any enum constant lacks a rule. Discovering that a discount type has no
 * implementation at checkout time, on a real customer, is strictly worse than failing to boot.
 */
@Slf4j
@Component
public class DiscountRuleRegistry {

    private final Map<DiscountType, DiscountRule> rulesByType = new EnumMap<>(DiscountType.class);

    public DiscountRuleRegistry(List<DiscountRule> rules) {
        rules.forEach(rule -> rulesByType.put(rule.type(), rule));
    }

    @PostConstruct
    void assertEveryTypeIsImplemented() {
        for (DiscountType type : DiscountType.values()) {
            if (!rulesByType.containsKey(type)) {
                throw new IllegalStateException(
                        "No DiscountRule registered for DiscountType." + type);
            }
        }
        log.info("Discount rules registered: {}", rulesByType.keySet());
    }

    public DiscountRule ruleFor(DiscountType type) {
        DiscountRule rule = rulesByType.get(type);
        if (rule == null) {
            throw new IllegalStateException("No DiscountRule registered for " + type);
        }
        return rule;
    }
}
