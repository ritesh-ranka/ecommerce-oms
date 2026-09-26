package com.ecommerce.oms.common.error;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raised when a reservation cannot be satisfied. Carries a per-SKU shortfall breakdown so
 * the client can tell the customer exactly which line failed and by how much, rather than
 * a bare "out of stock".
 */
@Getter
public class InsufficientStockException extends ApiException {

    public record Shortfall(String sku, int requested, int available) {
    }

    public InsufficientStockException(List<Shortfall> shortfalls) {
        super(ErrorCode.INSUFFICIENT_STOCK, buildMessage(shortfalls));
        shortfalls.forEach(s -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sku", s.sku());
            detail.put("requested", s.requested());
            detail.put("available", s.available());
            withDetail(detail);
        });
    }

    public static InsufficientStockException forSku(String sku, int requested, int available) {
        return new InsufficientStockException(List.of(new Shortfall(sku, requested, available)));
    }

    private static String buildMessage(List<Shortfall> shortfalls) {
        if (shortfalls.size() == 1) {
            Shortfall s = shortfalls.get(0);
            return "Insufficient stock for %s: requested %d, available %d"
                    .formatted(s.sku(), s.requested(), s.available());
        }
        return "Insufficient stock for %d item(s) in the request".formatted(shortfalls.size());
    }
}
