package com.ecommerce.oms.common.config;

import com.ecommerce.oms.iam.security.JwtAuthFilter;
import com.ecommerce.oms.iam.security.SecurityErrorResponder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security.
 *
 * <p>Two properties are worth pointing at:
 *
 * <ol>
 *   <li>{@code anyRequest().authenticated()} is the default, and the public allow-list is
 *       short and explicit. A newly added endpoint is therefore protected unless someone
 *       deliberately opens it — the safe direction for a mistake to fall.</li>
 *   <li>Role checks live at the controller via {@code @PreAuthorize} (enabled by
 *       {@link EnableMethodSecurity}), while <em>ownership</em> checks live in the service
 *       layer. Path-level rules alone cannot express "customer A may not read customer B's
 *       order", which is the most common way commerce APIs leak data.</li>
 * </ol>
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_GET_PATHS = {
            "/api/v1/products/**",
            "/api/v1/categories/**"
    };

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/h2-console/**",
            "/actuator/health",
            "/actuator/info"
    };

    private final JwtAuthFilter jwtAuthFilter;
    private final SecurityErrorResponder securityErrorResponder;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No cookies or sessions are used, so CSRF has nothing to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())) // H2 console
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(securityErrorResponder)
                        .accessDeniedHandler(securityErrorResponder))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** Cost 10 — the Spring default, and what the seeded demo hashes were generated with. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
