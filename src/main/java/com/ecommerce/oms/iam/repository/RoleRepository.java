package com.ecommerce.oms.iam.repository;

import com.ecommerce.oms.iam.domain.Role;
import com.ecommerce.oms.iam.domain.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(RoleName name);
}
