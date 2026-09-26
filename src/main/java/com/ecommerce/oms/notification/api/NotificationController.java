package com.ecommerce.oms.notification.api;

import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.notification.service.NotificationService;
import com.ecommerce.oms.notification.service.NotificationService.NotificationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Messages the system has sent to the caller.
 *
 * <p>This endpoint is how the asynchronous pipeline becomes <em>verifiable</em> rather than merely claimed:
 * place an order, and the confirmation shows up here, produced after the checkout response had already
 * been returned.
 */
@Tag(name = "3. Notifications", description = "Messages sent to the authenticated customer")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "My notifications, newest first",
            description = """
                    Scoped to the authenticated customer. Produced by the outbox pipeline after the
                    triggering request completed, so this is the simplest way to confirm the downstream
                    work ran without blocking checkout.
                    """)
    public ResponseEntity<ApiResponse<PageResponse<NotificationView>>> mine(
            @AuthenticationPrincipal OmsUserPrincipal customer,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                notificationService.forUser(customer.getId(), pageable)));
    }
}
