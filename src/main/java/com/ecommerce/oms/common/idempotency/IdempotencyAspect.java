package com.ecommerce.oms.common.idempotency;

import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Proxy/AOP implementation of at-most-once request handling for {@link Idempotent}
 * endpoints.
 *
 * <p>This exists because double-submitting a checkout must not charge twice. It sits
 * <em>outside</em> the business transaction, so the claim on the key is durable before any
 * stock is reserved and survives a rollback of the checkout itself.
 *
 * <p>The replay path returns the stored JSON as a {@code JsonNode}. Generic type erasure
 * makes that safe at runtime: Spring MVC only needs something serialisable, and the
 * declared return type of the advised method is erased by the time the proxy returns.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotencyAspect {

    private static final String REPLAY_HEADER = "Idempotent-Replay";

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = currentRequest();
        String key = request.getHeader(idempotent.header());
        if (key == null || key.isBlank()) {
            throw ApiException.validation(
                    "%s header is required for this endpoint".formatted(idempotent.header()));
        }
        if (key.length() > 120) {
            throw ApiException.validation("%s must be at most 120 characters".formatted(idempotent.header()));
        }

        String endpoint = request.getMethod() + " " + request.getRequestURI();
        Long userId = currentUserId();
        String fingerprint = fingerprint(joinPoint, userId);

        IdempotencyRecord claim;
        try {
            claim = store.claim(key, endpoint, userId, fingerprint);
        } catch (DataIntegrityViolationException duplicateKey) {
            return handleExistingClaim(key, endpoint, fingerprint);
        }

        log.info("Idempotent request accepted: key={} endpoint={} user={}", key, endpoint, userId);
        try {
            Object result = joinPoint.proceed();
            store.complete(claim.getId(), statusOf(result), serialiseBody(result));
            return result;
        } catch (Throwable failure) {
            // Business or transient failure: drop the claim so the client can retry properly.
            store.release(claim.getId());
            log.warn("Idempotent request failed, claim released: key={} endpoint={} cause={}",
                    key, endpoint, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    // ------------------------------------------------------------------ replay handling

    private Object handleExistingClaim(String key, String endpoint, String fingerprint) {
        Optional<IdempotencyRecord> existing = store.find(key, endpoint);
        if (existing.isEmpty()) {
            // The winner rolled back between our failed insert and this read; ask for a retry.
            throw ApiException.of(ErrorCode.CONCURRENT_MODIFICATION,
                    "Concurrent request with the same Idempotency-Key, please retry");
        }

        IdempotencyRecord record = existing.get();
        if (!record.matches(fingerprint)) {
            log.warn("Idempotency key reused with a different body: key={} endpoint={}", key, endpoint);
            throw ApiException.of(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    ErrorCode.IDEMPOTENCY_KEY_REUSED.getDefaultMessage());
        }
        if (!record.isCompleted()) {
            log.warn("Idempotent request still in flight: key={} endpoint={}", key, endpoint);
            throw ApiException.of(ErrorCode.REQUEST_IN_PROGRESS,
                    ErrorCode.REQUEST_IN_PROGRESS.getDefaultMessage());
        }

        log.info("Replaying stored response for idempotency key={} endpoint={} status={}",
                key, endpoint, record.getResponseStatus());
        currentResponse().ifPresent(response -> response.setHeader(REPLAY_HEADER, "true"));

        int status = record.getResponseStatus() == null ? 200 : record.getResponseStatus();
        if (record.getResponseBody() == null) {
            return ResponseEntity.status(status).build();
        }
        try {
            return ResponseEntity.status(status).body(objectMapper.readTree(record.getResponseBody()));
        } catch (Exception cannotParse) {
            return ResponseEntity.status(status).body(record.getResponseBody());
        }
    }

    // ------------------------------------------------------------------ fingerprinting

    /**
     * Fingerprints only the {@code @RequestBody} arguments plus the caller id. Path
     * variables are already part of the endpoint key, and framework arguments such as the
     * authenticated principal are not serialisable in a stable way.
     */
    private String fingerprint(ProceedingJoinPoint joinPoint, Long userId) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Object[] args = joinPoint.getArgs();

        List<Object> payload = new ArrayList<>();
        for (int i = 0; i < args.length && i < parameterAnnotations.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof RequestBody) {
                    payload.add(args[i]);
                }
            }
        }

        try {
            String canonical = userId + "|" + objectMapper.writeValueAsString(payload);
            return sha256(canonical);
        } catch (Exception notSerialisable) {
            log.debug("Could not fingerprint request body, falling back to user-only fingerprint", notSerialisable);
            return sha256(String.valueOf(userId));
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    // ------------------------------------------------------------------ response capture

    private int statusOf(Object result) {
        if (result instanceof ResponseEntity<?> entity) {
            return entity.getStatusCode().value();
        }
        return HttpStatus.OK.value();
    }

    private String serialiseBody(Object result) {
        Object body = result instanceof ResponseEntity<?> entity ? entity.getBody() : result;
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception cannotSerialise) {
            log.warn("Could not store idempotent response body: {}", cannotSerialise.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ request context

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        throw new IllegalStateException("@Idempotent is only supported on HTTP request handlers");
    }

    private Optional<HttpServletResponse> currentResponse() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.ofNullable(attributes.getResponse());
        }
        return Optional.empty();
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (auth.getPrincipal() instanceof com.ecommerce.oms.iam.security.OmsUserPrincipal principal) {
            return principal.getId();
        }
        return null;
    }
}
