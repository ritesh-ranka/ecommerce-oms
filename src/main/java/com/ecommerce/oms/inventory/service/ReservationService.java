package com.ecommerce.oms.inventory.service;

import com.ecommerce.oms.common.config.OmsProperties;
import com.ecommerce.oms.common.error.InsufficientStockException;
import com.ecommerce.oms.common.error.InsufficientStockException.Shortfall;
import com.ecommerce.oms.inventory.allocation.*;
import com.ecommerce.oms.inventory.domain.*;
import com.ecommerce.oms.inventory.repository.InventoryItemRepository;
import com.ecommerce.oms.inventory.repository.StockLedgerEntryRepository;
import com.ecommerce.oms.inventory.repository.StockReservationRepository;
import com.ecommerce.oms.inventory.service.ReservationCommands.ReservationLine;
import com.ecommerce.oms.inventory.service.ReservationCommands.ReservationResult;
import com.ecommerce.oms.warehouse.domain.Warehouse;
import com.ecommerce.oms.warehouse.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single writer of stock in the system, and therefore the single place the oversell
 * guarantee has to hold.
 *
 * <h2>Why every stock movement funnels through here</h2>
 * Checkout, cancellation, return restock, TTL expiry, and admin adjustment all mutate the
 * same contended rows. If any one of them wrote {@code inventory_items} directly, the
 * locking discipline below would be advisory rather than guaranteed. Concentrating the
 * writes in one class is what makes "the same unit cannot be oversold" a property of the
 * system rather than a property of each caller remembering to be careful.
 *
 * <h2>Transaction propagation</h2>
 * These methods deliberately <em>join</em> the caller's transaction rather than starting
 * their own. That is what makes checkout's failure matrix work: if pricing or coupon
 * validation fails after stock was reserved, the caller's rollback withdraws the hold
 * immediately. Running in a separate committed transaction would leave stock stranded until
 * the TTL swept it, turning a validation error into fifteen minutes of lost availability.
 *
 * <h2>Isolation</h2>
 * READ COMMITTED is sufficient and is the default on both supported databases. The
 * correctness argument rests on the pessimistic row locks acquired by
 * {@link InventoryItemRepository#lockAllByIdInOrder}, not on a higher isolation level —
 * SERIALIZABLE would add abort-and-retry storms on hot SKUs without improving the guarantee.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final InventoryItemRepository inventoryItemRepository;
    private final StockReservationRepository reservationRepository;
    private final StockLedgerEntryRepository ledgerRepository;
    private final AllocationStrategyRegistry strategyRegistry;
    private final WarehouseService warehouseService;
    private final OmsProperties properties;

    // =================================================================================
    // Phase 1 — reserve
    // =================================================================================

    /**
     * Holds stock for every line, or holds nothing at all.
     *
     * <p>Sequence, in order, and each step is load-bearing:
     * <ol>
     *   <li>merge duplicate lines, so the same SKU listed twice is checked against its total;</li>
     *   <li>discover candidate row ids <em>without</em> locking;</li>
     *   <li>lock all candidate rows in one statement, ordered by primary key;</li>
     *   <li>ask the configured {@link AllocationStrategy} where each line should come from;</li>
     *   <li>fail with the complete per-SKU shortfall if any line cannot be satisfied;</li>
     *   <li>apply the plan, writing a reservation and a ledger entry per allocation.</li>
     * </ol>
     *
     * <p>Steps 2–4 are the read-check-write cycle that concurrent checkouts race on. The lock
     * taken in step 3 is held until the caller's transaction commits, which is why that
     * transaction must not make network calls.
     *
     * @param reference       order number the hold is tagged with
     * @param lines           SKUs and quantities required
     * @param destinationZone shipping region, used only as an allocation preference
     * @throws InsufficientStockException with a per-SKU breakdown if any line is short
     */
    @Transactional
    public ReservationResult reserve(String reference, List<ReservationLine> lines, String destinationZone) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Cannot reserve stock for an empty line list");
        }

        Map<Long, Integer> quantityByVariant = mergeDuplicateLines(lines);
        Map<Long, String> skuByVariant = lines.stream().collect(Collectors.toMap(
                ReservationLine::variantId, ReservationLine::sku, (left, right) -> left));

        log.debug("Reserving stock for reference={} lines={} zone={}",
                reference, quantityByVariant, destinationZone);

        // Step 2 — candidate discovery, no locks held yet.
        List<Long> candidateIds = inventoryItemRepository.findIdsByVariantIds(quantityByVariant.keySet());

        // Step 3 — one statement, primary-key order: serialises same-SKU checkouts and makes
        // deadlock between two multi-SKU carts impossible.
        List<InventoryItem> lockedItems = candidateIds.isEmpty()
                ? List.of()
                : inventoryItemRepository.lockAllByIdInOrder(candidateIds);
        log.trace("Locked {} inventory row(s) for reference={}", lockedItems.size(), reference);

        Map<Long, InventoryItem> itemsById = lockedItems.stream()
                .collect(Collectors.toMap(InventoryItem::getId, Function.identity()));
        Map<Long, String> zoneByWarehouse = activeWarehouseZones();

        // Step 4 — decide, per line.
        AllocationStrategy strategy = strategyRegistry.active();
        List<AllocationPlan> plans = new ArrayList<>();
        List<Shortfall> shortfalls = new ArrayList<>();

        for (Map.Entry<Long, Integer> line : quantityByVariant.entrySet()) {
            Long variantId = line.getKey();
            int quantity = line.getValue();
            String sku = skuByVariant.getOrDefault(variantId, "variant-" + variantId);

            List<AllocationCandidate> candidates = candidatesFor(lockedItems, variantId, zoneByWarehouse);
            AllocationPlan plan = strategy.allocate(
                    new AllocationRequest(variantId, sku, quantity, destinationZone, candidates));
            plans.add(plan);

            if (!plan.isFullyAllocated()) {
                // A short plan has taken everything it could find, so what it managed to
                // allocate is exactly what is available network-wide for this SKU.
                int available = plan.allocatedQuantity();
                shortfalls.add(new Shortfall(sku, quantity, available));
                log.info("Stock shortfall for reference={} sku={} requested={} available={}",
                        reference, sku, quantity, available);
            }
        }

        // Step 5 — all-or-nothing, and report every failing line at once.
        if (!shortfalls.isEmpty()) {
            throw new InsufficientStockException(shortfalls);
        }

        // Step 6 — apply.
        Instant expiresAt = Instant.now().plus(properties.inventory().reservationTtl());
        List<Long> reservationIds = new ArrayList<>();

        for (AllocationPlan plan : plans) {
            for (Allocation allocation : plan.allocations()) {
                InventoryItem item = itemsById.get(allocation.inventoryItemId());
                item.reserve(allocation.quantity());

                StockReservation reservation = reservationRepository.save(
                        StockReservation.pending(reference, item, allocation.quantity(), expiresAt));
                reservationIds.add(reservation.getId());

                ledgerRepository.save(StockLedgerEntry.record(item, StockMovementType.RESERVE,
                        allocation.quantity(), reference,
                        "Held for checkout, expires " + expiresAt));

                log.debug("Reserved {} x{} from warehouse={} item={} (available now {})",
                        plan.sku(), allocation.quantity(), allocation.warehouseId(),
                        item.getId(), item.available());
            }
        }

        inventoryItemRepository.saveAll(itemsById.values());
        log.info("Reserved {} line(s) as {} reservation(s) for reference={}, expires at {}",
                plans.size(), reservationIds.size(), reference, expiresAt);

        return new ReservationResult(reference, reservationIds, plans);
    }

    /** Links holds to their order once the order row exists, inside the same transaction. */
    @Transactional
    public void attachToOrder(List<Long> reservationIds, Long orderId) {
        List<StockReservation> reservations = reservationRepository.findAllByIdInOrder(reservationIds);
        reservations.forEach(reservation -> reservation.attachToOrder(orderId));
        reservationRepository.saveAll(reservations);
        log.debug("Attached {} reservation(s) to orderId={}", reservations.size(), orderId);
    }

    // =================================================================================
    // Phase 2 — settle
    // =================================================================================

    /**
     * Turns holds into sales: {@code onHand -= qty} and {@code reserved -= qty}.
     *
     * <p>Called only after the payment gateway has authorised, so this is the point at which
     * stock physically leaves the warehouse in the model.
     */
    @Transactional
    public void commit(List<Long> reservationIds) {
        settle(reservationIds, ReservationStatus.COMMITTED, StockMovementType.SALE,
                "Sale settled after payment capture");
    }

    /**
     * Withdraws holds: {@code reserved -= qty}, {@code onHand} untouched.
     *
     * <p>Used by payment decline, customer cancellation, and the TTL sweeper alike — one
     * compensation path rather than three subtly different ones.
     */
    @Transactional
    public void release(List<Long> reservationIds) {
        settle(reservationIds, ReservationStatus.RELEASED, StockMovementType.RELEASE,
                "Hold released");
    }

    @Transactional
    public void expire(List<Long> reservationIds) {
        settle(reservationIds, ReservationStatus.EXPIRED, StockMovementType.EXPIRE,
                "Hold expired and swept");
    }

    /** Compensation entry point for a cancelled order, which knows its id but not its holds. */
    @Transactional
    public int releaseForOrder(Long orderId) {
        List<Long> pendingIds = reservationRepository
                .findByOrderIdAndStatus(orderId, ReservationStatus.PENDING)
                .stream().map(StockReservation::getId).toList();
        if (pendingIds.isEmpty()) {
            log.debug("No stock holds to release for orderId={}", orderId);
            return 0;
        }
        release(pendingIds);
        return pendingIds.size();
    }

    /**
     * Shared settlement path for all three terminal outcomes.
     *
     * <p>Re-locks the affected rows in primary-key order for the same reason
     * {@code reserve} does: commit and release are themselves concurrent with other
     * checkouts touching the same SKUs.
     *
     * <p>Reservations that already left PENDING are skipped rather than failed. A retried
     * compensation — which the outbox retry and the sweeper can both produce — must be a
     * no-op, not a double-release that would silently inflate available stock.
     */
    private void settle(List<Long> reservationIds, ReservationStatus targetStatus,
                        StockMovementType movementType, String note) {
        if (reservationIds == null || reservationIds.isEmpty()) {
            return;
        }

        List<StockReservation> reservations = reservationRepository.findAllByIdInOrder(reservationIds);
        List<StockReservation> actionable = reservations.stream()
                .filter(StockReservation::holdsStock)
                .toList();

        int alreadySettled = reservations.size() - actionable.size();
        if (alreadySettled > 0) {
            log.debug("Skipping {} reservation(s) already settled (idempotent {})",
                    alreadySettled, targetStatus);
        }
        if (actionable.isEmpty()) {
            return;
        }

        List<Long> itemIds = actionable.stream()
                .map(reservation -> reservation.getInventoryItem().getId())
                .distinct()
                .sorted()
                .toList();
        Map<Long, InventoryItem> itemsById = inventoryItemRepository.lockAllByIdInOrder(itemIds).stream()
                .collect(Collectors.toMap(InventoryItem::getId, Function.identity()));

        for (StockReservation reservation : actionable) {
            InventoryItem item = itemsById.get(reservation.getInventoryItem().getId());
            int quantity = reservation.getQuantity();

            switch (targetStatus) {
                case COMMITTED -> {
                    item.commitReserved(quantity);
                    reservation.markCommitted();
                }
                case RELEASED -> {
                    item.releaseReserved(quantity);
                    reservation.markReleased();
                }
                case EXPIRED -> {
                    item.releaseReserved(quantity);
                    reservation.markExpired();
                }
                default -> throw new IllegalArgumentException(
                        "Not a settlement status: " + targetStatus);
            }

            int delta = targetStatus == ReservationStatus.COMMITTED ? -quantity : quantity;
            ledgerRepository.save(StockLedgerEntry.record(item, movementType, delta,
                    reservation.getReference(), note));

            log.debug("{} reservation={} qty={} item={} -> onHand={} reserved={}",
                    targetStatus, reservation.getId(), quantity, item.getId(),
                    item.getOnHand(), item.getReserved());
        }

        inventoryItemRepository.saveAll(itemsById.values());
        reservationRepository.saveAll(actionable);
        log.info("Settled {} reservation(s) as {}", actionable.size(), targetStatus);
    }

    // =================================================================================
    // Helpers
    // =================================================================================

    /** Same SKU listed twice must be validated against its combined quantity, not twice over. */
    private Map<Long, Integer> mergeDuplicateLines(List<ReservationLine> lines) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        lines.forEach(line -> merged.merge(line.variantId(), line.quantity(), Integer::sum));
        return merged;
    }

    /**
     * Zones for every active warehouse, resolved once per call.
     *
     * <p>Loading these up front is what lets the allocation strategies stay pure: they receive
     * the zone as data instead of walking {@code item.getWarehouse().getZone()} and issuing a
     * query per candidate row.
     */
    private Map<Long, String> activeWarehouseZones() {
        return warehouseService.activeWarehouses().stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getZone));
    }

    /**
     * Flattens locked rows into allocation candidates.
     *
     * <p>Only {@code getId()} is read from the lazy warehouse association, which Hibernate
     * answers from the proxy without a query. Rows in inactive warehouses are dropped here, so
     * deactivating a warehouse removes it from allocation without touching its stock or its
     * shipment history.
     */
    private List<AllocationCandidate> candidatesFor(List<InventoryItem> lockedItems, Long variantId,
                                                    Map<Long, String> zoneByWarehouse) {
        return lockedItems.stream()
                .filter(item -> item.getVariant().getId().equals(variantId))
                .filter(item -> zoneByWarehouse.containsKey(item.getWarehouse().getId()))
                .map(item -> new AllocationCandidate(
                        item.getId(),
                        item.getWarehouse().getId(),
                        zoneByWarehouse.get(item.getWarehouse().getId()),
                        item.available()))
                .toList();
    }
}
