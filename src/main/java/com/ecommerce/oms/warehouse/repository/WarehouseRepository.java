package com.ecommerce.oms.warehouse.repository;

import com.ecommerce.oms.warehouse.domain.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    Optional<Warehouse> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<Warehouse> findByActiveTrueOrderByCodeAsc();
}
