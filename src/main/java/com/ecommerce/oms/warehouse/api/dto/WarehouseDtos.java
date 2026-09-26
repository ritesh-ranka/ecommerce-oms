package com.ecommerce.oms.warehouse.api.dto;

import com.ecommerce.oms.warehouse.domain.Warehouse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class WarehouseDtos {

    private WarehouseDtos() {
    }

    @Schema(name = "WarehouseRequest")
    public record WarehouseRequest(
            @NotBlank @Size(max = 30)
            @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "Code may contain letters, digits, and hyphens only")
            @Schema(example = "WH-HYD") String code,

            @NotBlank @Size(max = 120) @Schema(example = "Hyderabad Fulfillment Center") String name,

            @NotBlank @Size(max = 40)
            @Schema(description = "Coarse region, matched against the shipping address zone",
                    example = "SOUTH") String zone,

            @NotBlank @Size(max = 80) @Schema(example = "Hyderabad") String city,

            @Schema(description = "Inactive warehouses are excluded from allocation", example = "true")
            Boolean active
    ) {
    }

    @Schema(name = "WarehouseResponse")
    public record WarehouseResponse(
            Long id,
            String code,
            String name,
            String zone,
            String city,
            boolean active
    ) {
        public static WarehouseResponse from(Warehouse warehouse) {
            return new WarehouseResponse(warehouse.getId(), warehouse.getCode(), warehouse.getName(),
                    warehouse.getZone(), warehouse.getCity(), warehouse.isActive());
        }
    }

    @Schema(name = "StaffAssignmentRequest")
    public record StaffAssignmentRequest(
            @NotNull @Schema(description = "User id to assign to this warehouse", example = "3")
            Long userId
    ) {
    }
}
