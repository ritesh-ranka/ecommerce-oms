package com.ecommerce.oms.inventory.allocation;

import com.ecommerce.oms.common.config.OmsProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the configured {@link AllocationStrategy} by name (Registry pattern).
 *
 * <p>Spring injects every strategy bean it can find, so adding a third policy means adding
 * one class and one config value — no {@code switch} here grows, and no existing file is
 * edited. The alternative, a factory with a hard-coded branch per strategy, is the usual
 * reason "pluggable" behaviour stops being pluggable.
 *
 * <p>An unknown configured name fails at startup rather than at the first checkout. A typo in
 * an allocation policy is exactly the kind of mistake that must not wait for production
 * traffic to surface.
 */
@Slf4j
@Component
public class AllocationStrategyRegistry {

    private final Map<String, AllocationStrategy> strategiesByName;
    private final String configuredName;

    public AllocationStrategyRegistry(List<AllocationStrategy> strategies, OmsProperties properties) {
        this.strategiesByName = strategies.stream()
                .collect(Collectors.toMap(AllocationStrategy::name, Function.identity()));
        this.configuredName = properties.inventory().allocationStrategy();
    }

    @PostConstruct
    void validateConfiguration() {
        if (!strategiesByName.containsKey(configuredName)) {
            throw new IllegalStateException(
                    "oms.inventory.allocation-strategy=%s is not a known strategy. Available: %s"
                            .formatted(configuredName, strategiesByName.keySet()));
        }
        log.info("Allocation strategy in use: {} (available: {})", configuredName, strategiesByName.keySet());
    }

    /** The policy every checkout uses. */
    public AllocationStrategy active() {
        return strategiesByName.get(configuredName);
    }

    /** Escape hatch for tests that need to exercise a specific policy. */
    public AllocationStrategy byName(String name) {
        AllocationStrategy strategy = strategiesByName.get(name);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown allocation strategy: " + name);
        }
        return strategy;
    }
}
