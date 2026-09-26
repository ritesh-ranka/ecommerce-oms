package com.ecommerce.oms.iam.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "roles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "name", nullable = false, length = 40, unique = true)
    private RoleName name;

    public static Role of(RoleName name) {
        Role role = new Role();
        role.name = name;
        return role;
    }

    @Override
    public String toString() {
        return name.name();
    }
}
