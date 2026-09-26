package com.ecommerce.oms.common.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Thin success envelope. Keeps every 2xx body the same shape so a client has one
 * unwrapping rule, and gives endpoints a place to attach a human-readable message
 * (for example, "Reservation released") without inventing a DTO for it.
 *
 * <p>Errors deliberately use a different shape,
 * {@link com.ecommerce.oms.common.error.ApiError}, because an error has no {@code data}.
 */
@Schema(name = "ApiResponse", description = "Envelope for successful responses")
public record ApiResponse<T>(boolean success, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, message, data);
    }

    public static ApiResponse<Void> message(String message) {
        return new ApiResponse<>(true, message, null);
    }
}
