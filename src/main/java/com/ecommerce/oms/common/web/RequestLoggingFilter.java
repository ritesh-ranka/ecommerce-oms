package com.ecommerce.oms.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * First filter in the chain: assigns a trace id, publishes it to MDC and to the response
 * header, and logs one line on entry and one on exit with method, path, status, and
 * elapsed time.
 *
 * <p>Runs before Spring Security so that authentication failures are logged with a trace
 * id too. The authenticated principal is only known after the chain has run, so the user
 * id is added to MDC on the way out — which is why the completion line carries it and the
 * entry line does not.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final int SLOW_REQUEST_THRESHOLD_MS = 1_000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(TraceContext.TRACE_HEADER);
        String traceId = (incoming != null && !incoming.isBlank())
                ? incoming.trim()
                : TraceContext.newTraceId();

        TraceContext.putTraceId(traceId);
        TraceContext.putUserId(null);
        response.setHeader(TraceContext.TRACE_HEADER, traceId);

        long startedAt = System.nanoTime();
        String method = request.getMethod();
        String path = fullPath(request);

        log.debug("--> {} {}", method, path);
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            TraceContext.putUserId(currentPrincipalId());

            int status = response.getStatus();
            if (status >= 500) {
                log.error("<-- {} {} {} ({} ms)", method, path, status, elapsedMs);
            } else if (status >= 400) {
                log.warn("<-- {} {} {} ({} ms)", method, path, status, elapsedMs);
            } else if (elapsedMs > SLOW_REQUEST_THRESHOLD_MS) {
                log.warn("<-- {} {} {} ({} ms) SLOW", method, path, status, elapsedMs);
            } else {
                log.info("<-- {} {} {} ({} ms)", method, path, status, elapsedMs);
            }
            TraceContext.clear();
        }
    }

    /** Swagger and actuator traffic would otherwise bury the interesting lines. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private String fullPath(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private Object currentPrincipalId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getName();
    }
}
