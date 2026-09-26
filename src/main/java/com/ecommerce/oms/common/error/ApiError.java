package com.ecommerce.oms.common.error;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The one and only error body shape returned by this API.
 *
 * <pre>
 * {
 *   "timestamp": "2026-09-26T14:12:03Z",
 *   "status": 409,
 *   "code": "INSUFFICIENT_STOCK",
 *   "message": "Insufficient stock for TEE-BLK-M: requested 3, available 1",
 *   "details": [ { "sku": "TEE-BLK-M", "requested": 3, "available": 1 } ],
 *   "path": "/api/v1/checkout",
 *   "traceId": "b7c1e2f0"
 * }
 * </pre>
 */
@Schema(name = "ApiError", description = "Uniform error response")
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        List<Map<String, Object>> details,
        String path,
        String traceId
) {

    public static ApiError of(ErrorCode code, String message,
                              List<Map<String, Object>> details, String path, String traceId) {
        return new ApiError(
                Instant.now(),
                code.getStatus().value(),
                code.name(),
                message,
                details == null || details.isEmpty() ? null : details,
                path,
                traceId);
    }
}
