package com.ecommerce.oms.iam.service;

import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.iam.api.dto.AuthDtos.*;
import com.ecommerce.oms.iam.domain.Role;
import com.ecommerce.oms.iam.domain.RoleName;
import com.ecommerce.oms.iam.domain.User;
import com.ecommerce.oms.iam.repository.RoleRepository;
import com.ecommerce.oms.iam.repository.UserRepository;
import com.ecommerce.oms.iam.security.JwtService;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registration and token issuance.
 *
 * <p>Self-registration always produces a {@code CUSTOMER}. Privileged roles are assigned by
 * an administrator or by the seed migration — allowing a caller to pick their own role at
 * registration would make the entire RBAC model decorative.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase().trim();
        log.info("Registration attempt for email={}", email);

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.duplicate("An account already exists for " + email);
        }

        Role customerRole = roleRepository.findByName(RoleName.ROLE_CUSTOMER)
                .orElseThrow(() -> new IllegalStateException(
                        "ROLE_CUSTOMER is missing; check the V4 seed migration"));

        User user = userRepository.save(User.create(
                email,
                passwordEncoder.encode(request.password()),
                request.fullName(),
                request.phone(),
                request.zone(),
                customerRole));

        log.info("Registered userId={} email={} role=ROLE_CUSTOMER", user.getId(), email);
        return issueFor(user);
    }

    /**
     * Verifies credentials and mints a token.
     *
     * <p>Both "unknown email" and "wrong password" raise the same exception with the same
     * message. Distinguishing them would turn the endpoint into an account enumeration
     * oracle.
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        String email = request.email().toLowerCase().trim();
        log.info("Login attempt for email={}", email);

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> {
                    log.warn("Login failed: no account for email={}", email);
                    return new BadCredentialsException("Email or password is incorrect");
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Login failed: bad password for userId={}", user.getId());
            throw new BadCredentialsException("Email or password is incorrect");
        }
        if (!user.isEnabled()) {
            log.warn("Login failed: userId={} is disabled", user.getId());
            throw new DisabledException("Account is disabled");
        }

        log.info("Login succeeded for userId={} roles={}", user.getId(), user.roleNames());
        return issueFor(user);
    }

    @Transactional(readOnly = true)
    public UserSummary currentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));
        return toSummary(user);
    }

    // ------------------------------------------------------------------ helpers

    private TokenResponse issueFor(User user) {
        OmsUserPrincipal principal = OmsUserPrincipal.from(user);
        String token = jwtService.issueToken(principal);
        return TokenResponse.bearer(token, jwtService.tokenTtl().toSeconds(), toSummary(user));
    }

    private UserSummary toSummary(User user) {
        Set<String> roles = user.roleNames().stream().map(RoleName::name).collect(Collectors.toSet());
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName(),
                user.getZone(), roles, Set.copyOf(user.getWarehouseIds()));
    }
}
