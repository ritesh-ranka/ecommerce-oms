package com.ecommerce.oms.iam.security;

import com.ecommerce.oms.common.error.ApiError;
import com.ecommerce.oms.common.error.ErrorCode;
import com.ecommerce.oms.common.web.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Security failures happen inside the filter chain, before {@code @RestControllerAdvice}
 * can see them. Without this component a 401 would be Spring's default HTML/empty body
 * while every other error is {@link ApiError} — one API, two error contracts.
 *
 * <p>Implementing both callbacks here keeps the promise that every non-2xx response from
 * this service has the same shape and carries the same trace id.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.warn("[UNAUTHENTICATED] {} {} — no usable credentials",
                request.getMethod(), request.getRequestURI());
        write(response, request, ErrorCode.UNAUTHENTICATED,
                "Authentication is required. Obtain a token from POST /api/v1/auth/login.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("[FORBIDDEN] {} {} — role does not permit this operation",
                request.getMethod(), request.getRequestURI());
        write(response, request, ErrorCode.FORBIDDEN,
                "Your role does not permit this operation.");
    }

    private void write(HttpServletResponse response, HttpServletRequest request,
                       ErrorCode code, String message) throws IOException {
        ApiError body = ApiError.of(code, message, List.of(),
                request.getRequestURI(), TraceContext.currentTraceId());
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
