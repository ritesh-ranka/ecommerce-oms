package com.ecommerce.oms.outbox.handler;

import com.ecommerce.oms.audit.service.AuditService;
import com.ecommerce.oms.order.event.OrderEvents;
import com.ecommerce.oms.outbox.domain.OutboxEvent;
import com.ecommerce.oms.outbox.service.OutboxHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Writes the audit trail.
 *
 * <p>Third downstream consumer, and the one with the strongest reason to be independent of the other two:
 * a failed notification or a shipment conflict must never cost us the record of what happened. Its own
 * transaction guarantees that.
 *
 * <p>Subscribes to every order event. Unlike the notification handler, which filters to changes customers
 * care about, an audit trail with gaps is worth very little — the value is in being able to reconstruct
 * the whole history.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTrailHandler implements OutboxHandler {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Override
    public String handlerName() {
        return "audit-trail";
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
            case OrderEvents.ORDER_PLACED -> {
                OrderEvents.OrderPlaced placed = read(event, OrderEvents.OrderPlaced.class);
                auditService.record(placed.customerId(), placed.customerEmail(),
                        "ORDER_PLACED", OrderEvents.AGGREGATE_TYPE, placed.orderId(),
                        null, "CONFIRMED",
                        "Total %s, %d line(s), warehouses %s".formatted(
                                placed.grandTotal(), placed.lineCount(), placed.warehouseQuantities().keySet()));
            }
            case OrderEvents.ORDER_STATUS_CHANGED -> {
                OrderEvents.OrderStatusChanged changed = read(event, OrderEvents.OrderStatusChanged.class);
                auditService.record(null, changed.actor(),
                        "ORDER_STATUS_CHANGED", OrderEvents.AGGREGATE_TYPE, changed.orderId(),
                        changed.fromStatus() == null ? null : changed.fromStatus().name(),
                        changed.toStatus() == null ? null : changed.toStatus().name(),
                        changed.reason());
            }
            case OrderEvents.ORDER_CANCELLED -> {
                OrderEvents.OrderCancelled cancelled = read(event, OrderEvents.OrderCancelled.class);
                auditService.record(cancelled.customerId(), null,
                        "ORDER_CANCELLED", OrderEvents.AGGREGATE_TYPE, cancelled.orderId(),
                        null, "CANCELLED",
                        "%s | refunded %s | stockReleased=%s".formatted(
                                cancelled.reason(), cancelled.refundAmount(), cancelled.stockReleased()));
            }
            case OrderEvents.RETURN_SETTLED -> {
                OrderEvents.ReturnSettled settled = read(event, OrderEvents.ReturnSettled.class);
                auditService.record(settled.customerId(), null,
                        "RETURN_SETTLED", OrderEvents.AGGREGATE_TYPE, settled.orderId(),
                        "RETURN_REQUESTED", settled.fullyReturned() ? "RETURNED" : "DELIVERED",
                        "Return %d: %d unit(s), refunded %s".formatted(
                                settled.returnRequestId(), settled.unitsReturned(), settled.refundAmount()));
            }
            default -> log.warn("{} received an unexpected event type {}",
                    handlerName(), event.getEventType());
        }
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
