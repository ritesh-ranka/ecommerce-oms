package com.ecommerce.oms.iam.security;

import com.ecommerce.oms.iam.domain.RoleName;
import com.ecommerce.oms.iam.domain.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The authenticated caller, as seen by controllers via
 * {@code @AuthenticationPrincipal OmsUserPrincipal}.
 *
 * <p>Built entirely from JWT claims, so no database round trip happens per request. It
 * carries the user's warehouse assignment because fulfillment authorization needs to answer
 * "is this shipment in a warehouse you work at?" without another query.
 */
@Getter
public class OmsUserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;
    private final Set<RoleName> roles;
    private final Set<Long> warehouseIds;

    public OmsUserPrincipal(Long id, String email, String passwordHash, boolean enabled,
                            Set<RoleName> roles, Set<Long> warehouseIds) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.roles = roles;
        this.warehouseIds = warehouseIds;
    }

    public static OmsUserPrincipal from(User user) {
        return new OmsUserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isEnabled(),
                user.roleNames(),
                Set.copyOf(user.getWarehouseIds()));
    }

    public boolean hasRole(RoleName roleName) {
        return roles.contains(roleName);
    }

    public boolean isAdmin() {
        return hasRole(RoleName.ROLE_ADMIN);
    }

    public boolean worksAt(Long warehouseId) {
        return warehouseId != null && warehouseIds.contains(warehouseId);
    }

    // ------------------------------------------------------------------ UserDetails

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority(role.name()))
                .collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** Spring's notion of "username"; this system authenticates by email. */
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
