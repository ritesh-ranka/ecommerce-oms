package com.ecommerce.oms.order.api.dto;

import com.ecommerce.oms.common.domain.Address;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.domain.OrderLine;
import com.ecommerce.oms.order.domain.OrderStateMachine;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.payment.domain.Payment;
import com.ecommerce.oms.payment.domain.PaymentStatus;
import com.ecommerce.oms.payment.gateway.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class OrderDtos {

    private OrderDtos() {
    }

    // ------------------------------------------------------------------ requests

    @Schema(name = "AddressRequest")
    public record AddressRequest(
            @NotBlank @Size(max = 120) @Schema(example = "Cara Customer") String recipientName,
            @NotBlank @Size(max = 190) @Schema(example = "42 Marine Drive, Flat 9B") String line1,
            @NotBlank @Size(max = 80) @Schema(example = "Mumbai") String city,
            @NotBlank @Size(max = 40)
            @Schema(description = "Coarse region. Matched against warehouse zones to prefer the nearest stock.",
                    example = "WEST", allowableValues = {"WEST", "NORTH", "SOUTH", "EAST"}) String zone,
            @NotBlank @Size(max = 20) @Schema(example = "400020") String postalCode,
            @NotBlank @Size(max = 20) @Schema(example = "9000000002") String phone
    ) {
        public Address toAddress() {
            return new Address(recipientName, line1, city, zone, postalCode, phone);
        }
    }

    @Schema(name = "CheckoutRequest")
    public record CheckoutRequest(
            @NotNull @Valid AddressRequest shippingAddress,

            @NotNull @Schema(example = "CARD") PaymentMethod paymentMethod,

            @Size(max = 60)
            @Schema(description = """
                    Provider token standing in for card details. The simulated gateway is
                    deterministic: `tok_decline` forces a decline, `tok_timeout` an unknown outcome,
                    `tok_error` a transport failure, `tok_slow` a slow success. Anything else, or
                    omitted, succeeds.
                    """, example = "tok_success") String paymentToken,

            @Size(max = 40) @Schema(description = "Optional coupon. An invalid one returns 422 rather than being ignored.",
                    example = "SAVE10") String couponCode
    ) {
    }

    @Schema(name = "CancelOrderRequest")
    public record CancelOrderRequest(
            @Size(max = 255) @Schema(example = "Ordered the wrong size") String reason
    ) {
    }

    // ------------------------------------------------------------------ responses

    @Schema(name = "OrderLineResponse")
    public record OrderLineResponse(
            Long orderLineId,
            Long variantId,
            String sku,
            String productName,
            String variantName,
            int quantity,
            int returnedQuantity,
            int returnableQuantity,
            Money unitPrice,
            Money lineSubtotal,
            @Schema(description = "This line's apportioned share of the order discount") Money lineDiscount,
            Money lineTax,
            BigDecimal taxRate,
            Money lineTotal
    ) {
        static OrderLineResponse from(OrderLine line) {
            return new OrderLineResponse(line.getId(), line.getVariant().getId(), line.getSku(),
                    line.getProductName(), line.getVariantName(), line.getQuantity(),
                    line.getReturnedQuantity(), line.returnableQuantity(), line.getUnitPrice(),
                    line.getLineSubtotal(), line.getLineDiscount(), line.getLineTax(),
                    line.getTaxRate(), line.getLineTotal());
        }
    }

    @Schema(name = "PaymentResponse")
    public record PaymentResponse(
            PaymentStatus status,
            PaymentMethod method,
            Money amount,
            String gatewayReference,
            String failureReason,
            Instant authorizedAt,
            @Schema(description = "True for UNCONFIRMED payments, which an operator must reconcile")
            boolean needsReconciliation
    ) {
        public static PaymentResponse from(Payment payment) {
            return new PaymentResponse(payment.getStatus(), payment.getMethod(), payment.getCharged(),
                    payment.getGatewayReference(), payment.getFailureReason(), payment.getAuthorizedAt(),
                    payment.getStatus().needsReconciliation());
        }
    }

    @Schema(name = "OrderResponse", description = "Full order with lines, money breakdown, and payment state")
    public record OrderResponse(
            Long id,
            String orderNumber,
            Long customerId,
            OrderStatus status,
            @Schema(description = "Statuses this order may legally move to next, from the state machine")
            Set<OrderStatus> allowedNextStatuses,
            boolean cancellable,
            Money subtotal,
            Money discountTotal,
            Money taxTotal,
            Money shippingFee,
            Money grandTotal,
            Money refundedTotal,
            String discountCode,
            Address shippingAddress,
            Instant placedAt,
            Instant confirmedAt,
            Instant deliveredAt,
            Instant cancelledAt,
            String cancellationReason,
            PaymentResponse payment,
            List<OrderLineResponse> lines
    ) {
        public static OrderResponse from(Order order, Payment payment) {
            return new OrderResponse(
                    order.getId(), order.getOrderNumber(), order.getUserId(), order.getStatus(),
                    OrderStateMachine.allowedFrom(order.getStatus()), order.isCancellableByCustomer(),
                    order.getSubtotal(), order.getDiscountTotal(), order.getTaxTotal(),
                    order.getShippingFee(), order.getGrandTotal(), order.getRefundedTotal(),
                    order.getDiscountCode(), order.getShippingAddress(),
                    order.getPlacedAt(), order.getConfirmedAt(), order.getDeliveredAt(),
                    order.getCancelledAt(), order.getCancellationReason(),
                    payment == null ? null : PaymentResponse.from(payment),
                    order.getLines().stream().map(OrderLineResponse::from).toList());
        }
    }

    @Schema(name = "OrderSummary", description = "List row; omits lines and address")
    public record OrderSummary(
            Long id,
            String orderNumber,
            OrderStatus status,
            Money grandTotal,
            Money refundedTotal,
            int lineCount,
            Instant placedAt
    ) {
        public static OrderSummary from(Order order) {
            return new OrderSummary(order.getId(), order.getOrderNumber(), order.getStatus(),
                    order.getGrandTotal(), order.getRefundedTotal(), order.getLines().size(),
                    order.getPlacedAt());
        }
    }

    @Schema(name = "CheckoutResponse")
    public record CheckoutResponse(
            Long orderId,
            String orderNumber,
            OrderStatus status,
            Money grandTotal,
            PaymentResponse payment,
            @Schema(description = "Warehouses the order will ship from, decided by the allocation strategy")
            Set<Long> fulfillingWarehouseIds,
            String message
    ) {
    }
}
