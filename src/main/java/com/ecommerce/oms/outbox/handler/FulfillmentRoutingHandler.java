package com.ecommerce.oms.outbox.handler;

import com.ecommerce.oms.fulfillment.service.RoutingService;
import com.ecommerce.oms.order.event.OrderEvents;
import com.ecommerce.oms.outbox.domain.OutboxEvent;
import com.ecommerce.oms.outbox.service.OutboxHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Creates warehouse pick tasks once an order is confirmed, and voids them when it is cancelled.
 *
 * <p>First of the three downstream consumers that run after the checkout response. It reads the
 * allocation from the event payload rather than recomputing it — see {@link RoutingService} for why that
 * distinction matters.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FulfillmentRoutingHandler implements OutboxHandler {

    private final RoutingService routingService;
    private final ObjectMapper objectMapper;

    @Override
    public String handlerName() {
        return "fulfillment-routing";
    }

    @Override
    public Set<String> interestedIn() {
        return Set.of(OrderEvents.ORDER_PLACED, OrderEvents.ORDER_CANCELLED);
    }

    @Override
    public void handle(OutboxEvent event) {
        switch (event.getEventType()) {
            case OrderEvents.ORDER_PLACED -> route(event);
            case OrderEvents.ORDER_CANCELLED -> voidShipments(event);
            default -> log.warn("{} received an unexpected event type {}",
                    handlerName(), event.getEventType());
        }
    }

    private void route(OutboxEvent event) {
        OrderEvents.OrderPlaced placed = read(event, OrderEvents.OrderPlaced.class);
        routingService.routeOrder(placed.orderId(), placed.orderNumber(), placed.warehouseQuantities());
    }

    private void voidShipments(OutboxEvent event) {
        OrderEvents.OrderCancelled cancelled = read(event, OrderEvents.OrderCancelled.class);
        routingService.cancelShipments(cancelled.orderId(), cancelled.orderNumber());
    }

    /**
     * Deserialisation failure is not retryable — the payload will never become valid — but throwing lets
     * the event exhaust its attempts and land in DEAD_LETTER, where an admin can see it. That is the
     * right outcome: a malformed payload is a defect someone must look at, not something to discard.
     */
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
