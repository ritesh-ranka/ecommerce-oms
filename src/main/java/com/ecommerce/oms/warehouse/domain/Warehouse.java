package com.ecommerce.oms.warehouse.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A physical stocking location.
 *
 * <p>{@code zone} is the only proximity input the allocation strategy has. Real geocoding
 * is out of scope; matching a coarse region label against the shipping address is enough to
 * make "prefer the nearest warehouse" observable and testable without pulling in a
 * distance service.
 *
 * <p>Deactivating a warehouse removes it from allocation but preserves its inventory rows
 * and shipment history.
 */
@Entity
@Getter
@Table(name = "warehouses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Warehouse extends BaseEntity {

    @Column(name = "code", nullable = false, length = 30, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "zone", nullable = false, length = 40)
    private String zone;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public static Warehouse create(String code, String name, String zone, String city) {
        Warehouse warehouse = new Warehouse();
        warehouse.code = code.trim().toUpperCase();
        warehouse.name = name.trim();
        warehouse.zone = zone.trim().toUpperCase();
        warehouse.city = city.trim();
        warehouse.active = true;
        return warehouse;
    }

    public void update(String name, String zone, String city, boolean active) {
        this.name = name.trim();
        this.zone = zone.trim().toUpperCase();
        this.city = city.trim();
        this.active = active;
    }

    /** True when this warehouse is in the same coarse region as the destination. */
    public boolean servesZone(String targetZone) {
        return targetZone != null && zone.equalsIgnoreCase(targetZone.trim());
    }
}
