package com.ecommerce.oms.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * The single catalogue of machine-readable error codes, each bound to one HTTP status.
 *
 * <p>Clients branch on {@code code}, never on the prose message. Adding an error to the
 * API means adding a row here — there is nowhere else a status code is decided.
 */
@Getter
public enum ErrorCode {

    // --- 400 ----------------------------------------------------------------
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body could not be parsed"),

    // --- 401 / 403 ----------------------------------------------------------
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Access token is missing, malformed, or expired"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),

    // --- 402 ----------------------------------------------------------------
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED, "Payment was declined"),

    // --- 404 ----------------------------------------------------------------
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    // --- 409 ----------------------------------------------------------------
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Resource already exists"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Not enough stock to fulfil the request"),
    ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "That status transition is not allowed"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource changed concurrently, please retry"),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "An identical request is already being processed"),

    // --- 422 ----------------------------------------------------------------
    CART_EMPTY(HttpStatus.UNPROCESSABLE_ENTITY, "Cart is empty"),
    DISCOUNT_NOT_APPLICABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Discount code cannot be applied to this order"),
    RETURN_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "This order is not eligible for the requested return"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Request violates a business rule"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY,
            "This Idempotency-Key was already used with a different request body"),

    // --- 5xx ----------------------------------------------------------------
    PAYMENT_UNCONFIRMED(HttpStatus.BAD_GATEWAY,
            "Payment outcome is unconfirmed; the request is safe to retry with the same Idempotency-Key"),
    REFUND_FAILED(HttpStatus.BAD_GATEWAY, "Refund could not be completed at the gateway"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
