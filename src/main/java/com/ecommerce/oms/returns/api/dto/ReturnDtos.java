package com.ecommerce.oms.returns.api.dto;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.returns.domain.ReturnLine;
import com.ecommerce.oms.returns.domain.ReturnReason;
import com.ecommerce.oms.returns.domain.ReturnRequest;
import com.ecommerce.oms.returns.domain.ReturnStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class ReturnDtos {

    private ReturnDtos() {
    }

    // ------------------------------------------------------------------ requests

    @Schema(name = "ReturnLineRequest")
    public record ReturnLineRequest(
            @NotNull
            @Schema(description = "Order line id, from GET /orders/{id}", example = "1")
            Long orderLineId,

            @NotNull @Min(1)
            @Schema(description = "Units to return; must not exceed the line's returnableQuantity",
                    example = "2")
            Integer quantity
    ) {
    }

    @Schema(name = "CreateReturnRequest")
    public record CreateReturnRequest(
            @NotNull @Schema(example = "SIZE_MISMATCH") ReturnReason reason,

            @Size(max = 500) @Schema(example = "Ordered medium, needed large") String comment,

            @NotEmpty @Valid
            @Schema(description = "Lines and quantities to return. Partial returns are supported.")
            List<ReturnLineRequest> lines
    ) {
    }

    @Schema(name = "ResolveReturnRequest")
    public record ResolveReturnRequest(
            @Size(max = 500)
            @Schema(description = "Staff note recorded on the request and in the audit trail",
                    example = "Goods received in original packaging")
            String note
    ) {
    }

    // ------------------------------------------------------------------ responses

    @Schema(name = "ReturnLineResponse")
    public record ReturnLineResponse(
            Long orderLineId,
            int quantity,
            @Schema(description = "Refund attributable to these units: their share of the line total, discount and tax included")
            Money refund
    ) {
        static ReturnLineResponse from(ReturnLine line) {
            return new ReturnLineResponse(line.getOrderLineId(), line.getQuantity(), line.getRefund());
        }
    }

    @Schema(name = "ReturnResponse")
    public record ReturnResponse(
            Long id,
            Long orderId,
            ReturnStatus status,
            ReturnReason reason,
            String comment,
            String resolutionNote,
            int totalUnits,
            @Schema(description = "Total refund; only actually paid once the request is APPROVED")
            Money totalRefund,
            @Schema(description = "Whether the returned goods go back into sellable stock. Damaged and defective goods are refunded but not restocked.")
            boolean restockable,
            Instant requestedAt,
            Instant resolvedAt,
            List<ReturnLineResponse> lines
    ) {
        public static ReturnResponse from(ReturnRequest request) {
            return new ReturnResponse(
                    request.getId(), request.getOrderId(), request.getStatus(), request.getReason(),
                    request.getComment(), request.getResolutionNote(), request.totalUnits(),
                    request.totalRefund(), request.getReason().isResellable(),
                    request.getRequestedAt(), request.getResolvedAt(),
                    request.getLines().stream().map(ReturnLineResponse::from).toList());
        }
    }
}
