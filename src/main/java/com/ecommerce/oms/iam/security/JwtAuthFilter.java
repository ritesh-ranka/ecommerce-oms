package com.ecommerce.oms.iam.security;

import com.ecommerce.oms.common.web.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns a {@code Authorization: Bearer <jwt>} header into an authenticated
 * {@link org.springframework.security.core.context.SecurityContext}.
 *
 * <p>Does not reject anything itself. A missing or invalid token simply leaves the context
 * anonymous, and the authorization rules in {@code SecurityConfig} decide whether that is
 * acceptable for the requested path — which is what allows catalog browsing to stay public
 * while everything else requires a token.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            jwtService.parse(token).ifPresentOrElse(
                    principal -> {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        principal, null, principal.getAuthorities());
                        authentication.setDetails(
                                new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        TraceContext.putUserId(principal.getId());
                        log.debug("Authenticated userId={} roles={}", principal.getId(), principal.getRoles());
                    },
                    () -> log.debug("Bearer token present but not usable for {} {}",
                            request.getMethod(), request.getRequestURI()));
        }

        chain.doFilter(request, response);
    }

    /** Skips the filter entirely for paths that can never carry a meaningful token. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/h2-console");
    }
}
