package com.ecommerce.oms.iam.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * An account. Passwords are only ever held as a BCrypt hash; the plaintext never leaves
 * {@code AuthService}.
 *
 * <p>Warehouse assignment is modelled as a set of ids rather than a relation to the
 * {@code Warehouse} entity. That is deliberate: it keeps {@code iam} free of a compile-time
 * dependency on {@code warehouse}, preserving the rule that feature packages talk through
 * services rather than reaching into each other's tables.
 */
@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Column(name = "email", nullable = false, length = 190, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "phone", length = 20)
    private String phone;

    /** Coarse region label used as a proximity hint by the allocation strategy. */
    @Column(name = "zone", length = 40)
    private String zone;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "warehouse_staff", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "warehouse_id")
    private Set<Long> warehouseIds = new HashSet<>();

    public static User create(String email, String passwordHash, String fullName,
                             String phone, String zone, Role role) {
        User user = new User();
        user.email = email.toLowerCase().trim();
        user.passwordHash = passwordHash;
        user.fullName = fullName.trim();
        user.phone = phone;
        user.zone = zone;
        user.enabled = true;
        user.roles.add(role);
        return user;
    }

    public void assignWarehouse(Long warehouseId) {
        this.warehouseIds.add(warehouseId);
    }

    public boolean hasRole(RoleName roleName) {
        return roles.stream().anyMatch(role -> role.getName() == roleName);
    }

    public Set<RoleName> roleNames() {
        return roles.stream().map(Role::getName).collect(java.util.stream.Collectors.toSet());
    }
}
