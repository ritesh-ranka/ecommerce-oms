package com.ecommerce.oms.warehouse.service;

import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.iam.domain.RoleName;
import com.ecommerce.oms.iam.domain.User;
import com.ecommerce.oms.iam.repository.UserRepository;
import com.ecommerce.oms.warehouse.api.dto.WarehouseDtos.WarehouseRequest;
import com.ecommerce.oms.warehouse.api.dto.WarehouseDtos.WarehouseResponse;
import com.ecommerce.oms.warehouse.domain.Warehouse;
import com.ecommerce.oms.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<WarehouseResponse> findAll() {
        return warehouseRepository.findAll().stream().map(WarehouseResponse::from).toList();
    }

    /** Allocation candidates. Inactive locations are filtered out here, once, for every caller. */
    @Transactional(readOnly = true)
    public List<Warehouse> activeWarehouses() {
        return warehouseRepository.findByActiveTrueOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public Warehouse load(Long id) {
        return warehouseRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Warehouse", id));
    }

    @Transactional
    public WarehouseResponse create(WarehouseRequest request) {
        if (warehouseRepository.existsByCodeIgnoreCase(request.code())) {
            throw ApiException.duplicate("A warehouse already uses the code " + request.code());
        }
        Warehouse saved = warehouseRepository.save(
                Warehouse.create(request.code(), request.name(), request.zone(), request.city()));
        log.info("Warehouse created: id={} code={} zone={}", saved.getId(), saved.getCode(), saved.getZone());
        return WarehouseResponse.from(saved);
    }

    @Transactional
    public WarehouseResponse update(Long id, WarehouseRequest request) {
        Warehouse warehouse = load(id);
        boolean active = request.active() == null || request.active();
        warehouse.update(request.name(), request.zone(), request.city(), active);
        log.info("Warehouse updated: id={} code={} active={}", id, warehouse.getCode(), active);
        return WarehouseResponse.from(warehouse);
    }

    /**
     * Grants a staff member access to a warehouse's fulfillment queue.
     *
     * <p>The assignment is what makes {@code /fulfillment/**} safe: a staff member sees only
     * shipments in warehouses they are assigned to, so the role check alone is never the
     * whole authorization rule.
     */
    @Transactional
    public void assignStaff(Long warehouseId, Long userId) {
        Warehouse warehouse = load(warehouseId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));

        if (!user.hasRole(RoleName.ROLE_WAREHOUSE_STAFF)) {
            throw ApiException.businessRule(
                    "User %d must hold ROLE_WAREHOUSE_STAFF before being assigned to a warehouse".formatted(userId));
        }

        user.assignWarehouse(warehouse.getId());
        userRepository.save(user);
        log.info("Staff assigned: userId={} -> warehouseId={} ({})", userId, warehouseId, warehouse.getCode());
    }
}
