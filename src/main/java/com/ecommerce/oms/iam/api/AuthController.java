package com.ecommerce.oms.iam.api;

import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.iam.api.dto.AuthDtos.*;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.iam.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "1. Authentication", description = "Registration and JWT issuance")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(summary = "Register a customer account and receive a token",
            description = "Self-registration always yields ROLE_CUSTOMER. Privileged roles are seeded or assigned by an admin.")
    public ResponseEntity<ApiResponse<TokenResponse>> register(@Valid @RequestBody RegisterRequest request) {
        TokenResponse token = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(token, "Account created"));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange email and password for a bearer token",
            description = "Demo accounts: admin@oms.dev, customer@oms.dev, staff.mum@oms.dev — all with password Password@123")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(request)));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Describe the currently authenticated caller")
    public ResponseEntity<ApiResponse<UserSummary>> me(@AuthenticationPrincipal OmsUserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(authService.currentUser(principal.getId())));
    }
}
