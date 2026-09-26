package com.ecommerce.oms.common.error;

import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for every error this application raises deliberately.
 *
 * <p>Carries an {@link ErrorCode} (which owns the HTTP status) and an optional list of
 * structured detail entries. Field validation errors and domain errors both land in the
 * same {@code details} shape, so the API has exactly one error contract.
 */
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<Map<String, Object>> details = new ArrayList<>();

    public ApiException(ErrorCode code) {
        super(code.getDefaultMessage());
        this.code = code;
    }

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Fluent detail builder: {@code .with("sku", sku).with("available", n)}. */
    public ApiException with(String key, Object value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(key, value);
        this.details.add(entry);
        return this;
    }

    public ApiException withDetail(Map<String, Object> detail) {
        this.details.add(detail);
        return this;
    }

    public ApiException withDetails(List<Map<String, Object>> detailList) {
        this.details.addAll(detailList);
        return this;
    }

    // ------------------------------------------------------------------ factories
    // Focused named constructors keep call sites readable without a class per error.

    public static ApiException notFound(String entity, Object id) {
        return new ApiException(ErrorCode.NOT_FOUND, "%s not found: %s".formatted(entity, id));
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    public static ApiException duplicate(String message) {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException businessRule(String message) {
        return new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    public static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    public static ApiException illegalTransition(String message) {
        return new ApiException(ErrorCode.ILLEGAL_TRANSITION, message);
    }

    public static ApiException of(ErrorCode code, String message) {
        return new ApiException(code, message);
    }
}
