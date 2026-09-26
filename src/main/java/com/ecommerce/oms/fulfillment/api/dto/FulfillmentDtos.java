package com.ecommerce.oms.fulfillment.api.dto;

import com.ecommerce.oms.fulfillment.domain.Shipment;
import com.ecommerce.oms.fulfillment.domain.ShipmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class FulfillmentDtos {

    private FulfillmentDtos() {
    }

    @Schema(name = "ShipmentStatusUpdateRequest")
    public record ShipmentStatusUpdateRequest(
            @NotNull
            @Schema(description = "Next parcel state. PENDING -> PACKED -> SHIPPED -> DELIVERED.",
                    example = "PACKED",
                    allowableValues = {"PACKED", "SHIPPED", "DELIVERED", "CANCELLED"})
            ShipmentStatus status
    ) {
    }

    @Schema(name = "ShipmentResponse")
    public record ShipmentResponse(
            Long id,
            Long orderId,
            Long warehouseId,
            ShipmentStatus status,
            String trackingNumber,
            Instant packedAt,
            Instant shippedAt,
            Instant deliveredAt
    ) {
        public static ShipmentResponse from(Shipment shipment) {
            return new ShipmentResponse(shipment.getId(), shipment.getOrderId(),
                    shipment.getWarehouseId(), shipment.getStatus(), shipment.getTrackingNumber(),
                    shipment.getPackedAt(), shipment.getShippedAt(), shipment.getDeliveredAt());
        }
    }
}
