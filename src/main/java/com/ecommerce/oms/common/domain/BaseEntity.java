package com.ecommerce.oms.common.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * Identity and timestamps for every persisted entity.
 *
 * <p>Equality is by database identity only. Value-based equality on entities is a
 * well-known source of broken {@code HashSet} behaviour once an entity is persisted and
 * its fields mutate, so it is deliberately not used here.
 */
@Getter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isNew() {
        return id == null;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseEntity that) || !getClass().equals(other.getClass())) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return id != null ? id.hashCode() : Objects.hashCode(getClass());
    }
}
