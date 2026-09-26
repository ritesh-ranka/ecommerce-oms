package com.ecommerce.oms.iam.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Auth request and response payloads.
 *
 * <p>Grouped in one file because they are a single cohesive contract read together;
 * splitting five 6-line records across five files would add navigation cost and no clarity.
 * Validation lives on the DTO so malformed input is rejected before any service code runs.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(name = "RegisterRequest")
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 190)
            @Schema(example = "new.customer@example.com")
            String email,

            @NotBlank
            @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
            @Schema(example = "Password@123")
            String password,

            @NotBlank @Size(max = 120)
            @Schema(example = "New Customer")
            String fullName,

            @Pattern(regexp = "^$|^[0-9+\\-\\s]{7,20}$", message = "Phone must be 7-20 digits")
            @Schema(example = "9000000009")
            String phone,

            @Size(max = 40)
            @Schema(description = "Coarse region used as a warehouse proximity hint",
                    example = "WEST", allowableValues = {"WEST", "NORTH", "SOUTH", "EAST"})
            String zone
    ) {
    }

    @Schema(name = "LoginRequest")
    public record LoginRequest(
            @NotBlank @Email
            @Schema(example = "customer@oms.dev")
            String email,

            @NotBlank
            @Schema(example = "Password@123")
            String password
    ) {
    }

    @Schema(name = "TokenResponse")
    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            UserSummary user
    ) {
        public static TokenResponse bearer(String token, long expiresInSeconds, UserSummary user) {
            return new TokenResponse(token, "Bearer", expiresInSeconds, user);
        }
    }

    @Schema(name = "UserSummary")
    public record UserSummary(
            Long id,
            String email,
            String fullName,
            String zone,
            Set<String> roles,
            Set<Long> warehouseIds
    ) {
    }
}
