package com.ecommerce.oms.iam.security;

import com.ecommerce.oms.common.config.OmsProperties;
import com.ecommerce.oms.iam.domain.RoleName;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Issues and verifies HS256 access tokens.
 *
 * <p>Tokens are self-contained: roles and warehouse assignment travel as claims so request
 * authorization needs no database lookup, which is what keeps the service horizontally
 * scalable without session affinity.
 *
 * <p>This class is the single seam where advanced auth (OAuth/SSO) would attach. It is out
 * of scope per the brief, so the implementation is deliberately minimal but isolated.
 */
@Slf4j
@Service
public class JwtService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_WAREHOUSES = "warehouses";

    private final SecretKey signingKey;
    private final String issuer;
    private final Duration ttl;

    public JwtService(OmsProperties properties) {
        String secret = properties.security().jwt().secret();
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "oms.security.jwt.secret must be at least 32 bytes for HS256, got " + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.issuer = properties.security().jwt().issuer();
        this.ttl = properties.security().jwt().accessTokenTtl();
    }

    public String issueToken(OmsUserPrincipal principal) {
        Instant now = Instant.now();
        Instant expiry = now.plus(ttl);

        String token = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(principal.getId()))
                .claim(CLAIM_EMAIL, principal.getEmail())
                .claim(CLAIM_ROLES, principal.getRoles().stream().map(Enum::name).toList())
                .claim(CLAIM_WAREHOUSES, List.copyOf(principal.getWarehouseIds()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();

        log.debug("Issued access token for userId={} expiring at {}", principal.getId(), expiry);
        return token;
    }

    public Duration tokenTtl() {
        return ttl;
    }

    /**
     * Verifies signature and expiry and rebuilds the principal from claims.
     * Returns empty rather than throwing, because an invalid token is an expected
     * condition on a public internet endpoint, not an exceptional one.
     */
    public Optional<OmsUserPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get(CLAIM_EMAIL, String.class);
            Set<RoleName> roles = readList(claims, CLAIM_ROLES).stream()
                    .map(String::valueOf)
                    .map(RoleName::valueOf)
                    .collect(Collectors.toSet());
            Set<Long> warehouseIds = readList(claims, CLAIM_WAREHOUSES).stream()
                    .map(value -> ((Number) value).longValue())
                    .collect(Collectors.toSet());

            return Optional.of(new OmsUserPrincipal(userId, email, null, true, roles, warehouseIds));
        } catch (JwtException | IllegalArgumentException invalid) {
            log.debug("Rejected token: {}", invalid.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> readList(Claims claims, String name) {
        Object value = claims.get(name);
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
