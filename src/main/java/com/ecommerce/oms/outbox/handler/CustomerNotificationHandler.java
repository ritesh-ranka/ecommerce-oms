package com.ecommerce.oms.outbox.handler;

import com.ecommerce.oms.notification.service.NotificationService;
import com.ecommerce.oms.order.event.OrderEvents;
import com.ecommerce.oms.outbox.domain.OutboxEvent;
import com.ecommerce.oms.outbox.service.OutboxHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Tells the customer what happened.
 *
 * <p>Second of the three downstream consumers. Runs on its own transaction, so a notification failure
 * cannot roll back the pick task the routing handler just created, or the audit row written next.
 *
 * <p>Every message is composed from the event payload alone. Re-reading the order would be a bug: by the
 * time a retried event is processed the status may have moved on, and the customer would receive a
 * "confirmed" email describing an order that has since been cancelled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerNotificationHandler implements OutboxHandler {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public String handlerName() {
        return "customer-notification";
    }

    @Override
    public Set<String> interestedIn() {
        return Set.of(
                OrderEvents.ORDER_PLACED,
                OrderEvents.ORDER_STATUS_CHANGED,
                OrderEvents.ORDER_CANCELLED,
                OrderEvents.RETURN_SETTLED);
    }

    @Override
    public void handle(OutboxEvent event) {
        switch (event.getEventType()) {
            case OrderEvents.ORDER_PLACED -> onPlaced(event);
            case OrderEvents.ORDER_STATUS_CHANGED -> onStatusChanged(event);
            case OrderEvents.ORDER_CANCELLED -> onCancelled(event);
            case OrderEvents.RETURN_SETTLED -> onReturnSettled(event);
            default -> log.warn("{} received an unexpected event type {}",
                    handlerName(), event.getEventType());
        }
    }

    private void onPlaced(OutboxEvent event) {
        OrderEvents.OrderPlaced placed = read(event, OrderEvents.OrderPlaced.class);
        notificationService.notifyInApp(placed.customerId(),
                "Order %s confirmed".formatted(placed.orderNumber()),
                """
                Thanks for your order.

                Order number: %s
                Items: %d line(s), %d unit(s)
                Total paid: %s

                We are preparing it for dispatch and will let you know when it ships."""
                        .formatted(placed.orderNumber(), placed.lineCount(),
                                placed.totalUnits(), placed.grandTotal()));
    }

    private void onStatusChanged(OutboxEvent event) {
        OrderEvents.OrderStatusChanged changed = read(event, OrderEvents.OrderStatusChanged.class);

        // Not every internal transition is worth a message. Customers care about progress, not
        // bookkeeping, and over-notifying trains people to ignore the channel.
        String body = switch (changed.toStatus()) {
            case PACKED -> "Your order %s has been packed and is ready to leave our warehouse."
                    .formatted(changed.orderNumber());
            case SHIPPED -> "Your order %s is on its way."
                    .formatted(changed.orderNumber());
            case DELIVERED -> "Your order %s has been delivered. You can request a return from your order history."
                    .formatted(changed.orderNumber());
            case RETURN_REQUESTED -> "We have received your return request for order %s and are reviewing it."
                    .formatted(changed.orderNumber());
            default -> null;
        };

        if (body == null) {
            log.debug("No customer-facing message for {} -> {}",
                    changed.fromStatus(), changed.toStatus());
            return;
        }

        notificationService.notifyInApp(changed.customerId(),
                "Order %s: %s".formatted(changed.orderNumber(), changed.toStatus()), body);
    }

    private void onCancelled(OutboxEvent event) {
        OrderEvents.OrderCancelled cancelled = read(event, OrderEvents.OrderCancelled.class);
        String refundLine = cancelled.refundAmount() != null && cancelled.refundAmount().isPositive()
                ? "A refund of %s has been issued and will appear on your original payment method."
                        .formatted(cancelled.refundAmount())
                : "No payment had been captured, so there is nothing to refund.";

        notificationService.notifyInApp(cancelled.customerId(),
                "Order %s cancelled".formatted(cancelled.orderNumber()),
                """
                Your order %s has been cancelled.

                Reason: %s
                %s"""
                        .formatted(cancelled.orderNumber(), cancelled.reason(), refundLine));
    }

    private void onReturnSettled(OutboxEvent event) {
        OrderEvents.ReturnSettled settled = read(event, OrderEvents.ReturnSettled.class);
        notificationService.notifyInApp(settled.customerId(),
                "Return approved for order %s".formatted(settled.orderNumber()),
                """
                Your return has been approved.

                Order: %s
                Units returned: %d
                Refund issued: %s

                %s"""
                        .formatted(settled.orderNumber(), settled.unitsReturned(),
                                settled.refundAmount(),
                                settled.fullyReturned()
                                        ? "This completes the return of your whole order."
                                        : "The remaining items on this order are unaffected."));
    }

    private <T> T read(OutboxEvent event, Class<T> type) {
        try {
            return objectMapper.readValue(event.getPayload(), type);
        } catch (Exception malformed) {
            throw new IllegalStateException(
                    "Could not deserialise %s payload for outbox event %d"
                            .formatted(event.getEventType(), event.getId()), malformed);
        }
    }
}
