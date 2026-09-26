package com.ecommerce.oms.common.error;

import com.ecommerce.oms.common.web.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only place in the application that turns an exception into an HTTP response.
 *
 * <p>Field-level validation failures, domain rule violations, security failures, and
 * unexpected errors all leave through here and all emerge as {@link ApiError}, so the API
 * has exactly one error contract. Controllers contain no try/catch.
 *
 * <p>Logging policy is deliberate: 4xx is a client problem and is logged at WARN without a
 * stack trace (it is noise, not a defect); 5xx is our problem and is logged at ERROR with
 * the full stack trace.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ------------------------------------------------------------------ deliberate errors

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.getCode();
        if (code.getStatus().is5xxServerError()) {
            log.error("[{}] {} — {}", code, request.getRequestURI(), ex.getMessage(), ex);
        } else {
            log.warn("[{}] {} — {}", code, request.getRequestURI(), ex.getMessage());
        }
        return respond(code, ex.getMessage(), ex.getDetails(), request);
    }

    // ------------------------------------------------------------------ validation

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                        HttpServletRequest request) {
        List<Map<String, Object>> details = new ArrayList<>(ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> detail("field", fe.getField(), "rejectedValue", fe.getRejectedValue(),
                        "reason", fe.getDefaultMessage()))
                .toList());
        ex.getBindingResult().getGlobalErrors().forEach(ge ->
                details.add(detail("object", ge.getObjectName(), "reason", ge.getDefaultMessage())));

        log.warn("[VALIDATION_FAILED] {} — {} field error(s)", request.getRequestURI(), details.size());
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", details, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                             HttpServletRequest request) {
        List<Map<String, Object>> details = ex.getConstraintViolations().stream()
                .map(v -> detail("field", v.getPropertyPath().toString(),
                        "rejectedValue", v.getInvalidValue(), "reason", v.getMessage()))
                .toList();
        log.warn("[VALIDATION_FAILED] {} — {}", request.getRequestURI(), ex.getMessage());
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", details, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class
    })
    public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
        log.warn("[MALFORMED_REQUEST] {} — {}", request.getRequestURI(), ex.getMessage());
        return respond(ErrorCode.MALFORMED_REQUEST, rootMessage(ex), List.of(), request);
    }

    // ------------------------------------------------------------------ security

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                       HttpServletRequest request) {
        log.warn("[FORBIDDEN] {} — {}", request.getRequestURI(), ex.getMessage());
        return respond(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMessage(), List.of(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex,
                                                         HttpServletRequest request) {
        log.warn("[INVALID_CREDENTIALS] {} — login rejected", request.getRequestURI());
        return respond(ErrorCode.INVALID_CREDENTIALS,
                ErrorCode.INVALID_CREDENTIALS.getDefaultMessage(), List.of(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex,
                                                         HttpServletRequest request) {
        log.warn("[UNAUTHENTICATED] {} — {}", request.getRequestURI(), ex.getMessage());
        return respond(ErrorCode.UNAUTHENTICATED,
                ErrorCode.UNAUTHENTICATED.getDefaultMessage(), List.of(), request);
    }

    // ------------------------------------------------------------------ concurrency / data

    /**
     * An optimistic lock clash that survived the retry loop in the service layer. By the
     * time it reaches here it is a genuine conflict, so the client is told to retry rather
     * than being shown a 500.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleLockFailure(Exception ex, HttpServletRequest request) {
        log.warn("[CONCURRENT_MODIFICATION] {} — {}", request.getRequestURI(), ex.getMessage());
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                ErrorCode.CONCURRENT_MODIFICATION.getDefaultMessage(), List.of(), request);
    }

    /**
     * Includes the {@code ck_inventory_reserved_within_on_hand} backstop firing. If that
     * ever happens it means application-level guarding was bypassed, so it is logged at
     * ERROR even though the response is a 409.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {
        String root = rootMessage(ex);
        log.error("[DATA_INTEGRITY] {} — {}", request.getRequestURI(), root, ex);
        boolean oversell = root != null && root.toLowerCase().contains("ck_inventory_reserved");
        ErrorCode code = oversell ? ErrorCode.INSUFFICIENT_STOCK : ErrorCode.DUPLICATE_RESOURCE;
        String message = oversell
                ? "Stock invariant would be violated by this request"
                : "Request conflicts with existing data";
        return respond(code, message, List.of(), request);
    }

    // ------------------------------------------------------------------ fallbacks

    @ExceptionHandler({NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiError> handleNoHandler(Exception ex, HttpServletRequest request) {
        log.warn("[NOT_FOUND] {} — {}", request.getRequestURI(), ex.getMessage());
        return respond(ErrorCode.NOT_FOUND, "No endpoint matches " + request.getRequestURI(),
                List.of(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex,
                                                          HttpServletRequest request) {
        log.warn("[VALIDATION_FAILED] {} — {}", request.getRequestURI(), ex.getMessage());
        return respond(ErrorCode.VALIDATION_FAILED, ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("[INTERNAL_ERROR] {} — unhandled {}", request.getRequestURI(),
                ex.getClass().getSimpleName(), ex);
        // Deliberately does not leak the exception message to the client.
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                List.of(), request);
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<ApiError> respond(ErrorCode code, String message,
                                             List<Map<String, Object>> details,
                                             HttpServletRequest request) {
        HttpStatus status = code.getStatus();
        ApiError body = ApiError.of(code, message, details,
                request.getRequestURI(), TraceContext.currentTraceId());
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> detail(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return map;
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage();
    }
}
