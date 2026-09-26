package com.ecommerce.oms.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Shipping address value object, captured per order rather than stored in an address book
 * (documented assumption: customers supply one address at checkout).
 *
 * <p>{@code zone} is a coarse region label — WEST, NORTH, SOUTH — and is the only input to
 * warehouse proximity. Real geocoding is out of scope; the zone match is enough to make
 * the allocation strategy's preference ordering observable.
 */
@Getter
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Address {

    @Column(length = 120)
    private String recipientName;

    @Column(length = 190)
    private String line1;

    @Column(length = 80)
    private String city;

    @Column(length = 40)
    private String zone;

    @Column(length = 20)
    private String postalCode;

    @Column(length = 20)
    private String phone;

    @Override
    public String toString() {
        return "%s, %s, %s %s".formatted(line1, city, zone, postalCode);
    }
}
