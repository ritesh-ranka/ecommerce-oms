package com.ecommerce.oms.concurrency;

import com.ecommerce.oms.inventory.domain.InventoryItem;
import com.ecommerce.oms.inventory.domain.ReservationStatus;
import com.ecommerce.oms.inventory.repository.InventoryItemRepository;
import com.ecommerce.oms.inventory.repository.StockReservationRepository;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.repository.OrderRepository;
import com.ecommerce.oms.support.IntegrationTestBase;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test that proves requirement #1: <b>the same unit cannot be oversold across warehouses under
 * concurrent purchases.</b>
 *
 * <p>This is the headline test of the suite. Everything else in the inventory design — the reservation
 * ledger, the single-statement primary-key-ordered {@code SELECT ... FOR UPDATE}, the {@code @Version}
 * guard, the database {@code CHECK} constraint — exists to make these assertions hold, and none of it can
 * be verified by a single-threaded test.
 *
 * <h2>What makes it a real race</h2>
 * All threads are parked on a {@link CountDownLatch} and released simultaneously, so they contend inside
 * the same few milliseconds rather than queueing politely. Each one drives the full HTTP checkout path, not
 * the service in isolation, so the transaction boundaries, the gateway call outside the transaction, and
 * the settlement are all genuinely exercised under contention.
 *
 * <h2>What is asserted</h2>
 * Not merely "no exception". Four independent invariants are checked, because each catches a different
 * class of defect:
 * <ol>
 *   <li>exactly N successes and the rest conflicts — no phantom sales;</li>
 *   <li>{@code sum(on_hand) == 0} — the stock genuinely left;</li>
 *   <li>{@code sum(reserved) == 0} — no hold leaked from a losing thread;</li>
 *   <li>exactly N {@code COMMITTED} reservations — the ledger agrees with the counters.</li>
 * </ol>
 * A design that decremented a counter under an optimistic lock could satisfy (1) while failing (3).
 */
@Slf4j
class OversellPreventionIT extends IntegrationTestBase {

    private static final int CONTENDING_THREADS = 50;

    /**
     * Each test uses its own SKU. The three tests share one database and one Spring context, so a shared
     * SKU would let confirmed-order counts from one test leak into another's assertions — the classic way
     * a concurrency suite becomes order-dependent and intermittently green.
     */
    private static final long TEN_UNIT_VARIANT_ID = 10L;      // TEE-BLK-L
    private static final long PARTIAL_LINE_VARIANT_ID = 11L;  // TEE-WHT-M

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private StockReservationRepository reservationRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("50 simultaneous checkouts for 1 unit produce exactly 1 order and 49 conflicts")
    void singleUnitIsSoldExactlyOnce() throws Exception {
        // Stock is set explicitly rather than relying on the seed: these tests share a database and a
        // SKU, so depending on seeded values would make them order-dependent and intermittently green.
        // One unit split across two warehouses (1 and 0) is the scenario from the design docs.
        setStock(SCARCE_VARIANT_ID, WAREHOUSE_MUMBAI, 1);
        setStock(SCARCE_VARIANT_ID, 2L, 0);
        assertThat(totalAvailable(SCARCE_VARIANT_ID)).isEqualTo(1);

        Outcome outcome = raceForVariant(SCARCE_VARIANT_ID, 1);

        log.info("Oversell race finished: {} created, {} conflicts, {} other", outcome.created(),
                outcome.conflicts(), outcome.other());

        assertThat(outcome.created())
                .as("exactly one thread may win a single unit")
                .isEqualTo(1);
        assertThat(outcome.conflicts())
                .as("every other thread must be told the stock is gone")
                .isEqualTo(CONTENDING_THREADS - 1);
        assertThat(outcome.other())
                .as("no thread may fail for an unexpected reason")
                .isZero();

        assertStockFullyConsumed(SCARCE_VARIANT_ID, 1);
    }

    @Test
    @DisplayName("50 simultaneous checkouts for 10 units produce exactly 10 orders")
    void tenUnitsAreSoldExactlyTenTimes() throws Exception {
        // A different SKU from the other tests, so confirmed-order counts cannot bleed between them.
        // Split 6/4 across two warehouses, so winners are allocated from both and the deterministic lock
        // ordering is exercised across more than one row.
        setStock(TEN_UNIT_VARIANT_ID, WAREHOUSE_MUMBAI, 6);
        setStock(TEN_UNIT_VARIANT_ID, 2L, 4);
        assertThat(totalAvailable(TEN_UNIT_VARIANT_ID)).isEqualTo(10);

        Outcome outcome = raceForVariant(TEN_UNIT_VARIANT_ID, 1);

        assertThat(outcome.created())
                .as("exactly ten threads may win, one per available unit")
                .isEqualTo(10);
        assertThat(outcome.conflicts()).isEqualTo(CONTENDING_THREADS - 10);
        assertThat(outcome.other()).isZero();

        assertStockFullyConsumed(TEN_UNIT_VARIANT_ID, 10);
    }

    @Test
    @DisplayName("a multi-unit line is never partially sold")
    void multiUnitLineIsAllOrNothing() throws Exception {
        // 7 units available, each thread wants 3: at most 2 can succeed, and the odd unit must remain on
        // the shelf rather than a third order shipping 1 of the 3 units it asked for.
        setStock(PARTIAL_LINE_VARIANT_ID, WAREHOUSE_MUMBAI, 7);

        Outcome outcome = raceForVariant(PARTIAL_LINE_VARIANT_ID, 3);

        assertThat(outcome.created())
                .as("7 units at 3 per order allows exactly 2 orders")
                .isEqualTo(2);
        assertThat(outcome.other()).isZero();

        assertThat(totalAvailable(PARTIAL_LINE_VARIANT_ID))
                .as("the leftover unit must stay on the shelf, not be half-sold")
                .isEqualTo(1);
        assertThat(totalReserved(PARTIAL_LINE_VARIANT_ID))
                .as("no hold may leak from the 48 losing threads")
                .isZero();
        assertThat(orderRepository.countByStatusContainingVariant(
                OrderStatus.CONFIRMED, PARTIAL_LINE_VARIANT_ID))
                .isEqualTo(2);
    }

    // =================================================================================
    // Race harness
    // =================================================================================

    private record Outcome(int created, int conflicts, int other, List<String> unexpectedBodies) {
    }

    /**
     * Runs {@link #CONTENDING_THREADS} checkouts against one SKU, released simultaneously.
     *
     * <p>Each thread gets its own registered customer, because a cart is per-user: fifty threads sharing
     * one cart would contend on the cart rather than on the stock, and would measure the wrong thing.
     */
    private Outcome raceForVariant(long variantId, int quantityPerOrder) throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < CONTENDING_THREADS; i++) {
            String token = registerCustomer("race-%d-%s@example.com"
                    .formatted(i, java.util.UUID.randomUUID().toString().substring(0, 8)));
            addToCart(token, variantId, quantityPerOrder);
            tokens.add(token);
        }

        ExecutorService pool = Executors.newFixedThreadPool(CONTENDING_THREADS);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONTENDING_THREADS);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        List<String> unexpected = new CopyOnWriteArrayList<>();

        try {
            for (String token : tokens) {
                pool.submit(() -> {
                    try {
                        // Park every thread here so they all hit the database at once.
                        startGun.await();
                        MvcResult result = checkout(token, "tok_success", null);
                        int status = result.getResponse().getStatus();

                        if (status == HttpStatus.CREATED.value()) {
                            created.incrementAndGet();
                        } else if (status == HttpStatus.CONFLICT.value()) {
                            conflicts.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                            unexpected.add(status + " -> " + result.getResponse().getContentAsString());
                        }
                    } catch (Exception failure) {
                        other.incrementAndGet();
                        unexpected.add("exception: " + failure);
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGun.countDown();
            boolean allDone = finished.await(90, TimeUnit.SECONDS);
            assertThat(allDone).as("all %d checkout attempts must complete", CONTENDING_THREADS).isTrue();
        } finally {
            pool.shutdownNow();
        }

        if (!unexpected.isEmpty()) {
            log.error("Unexpected responses during the race: {}", unexpected);
        }
        return new Outcome(created.get(), conflicts.get(), other.get(), unexpected);
    }

    // =================================================================================
    // Invariant assertions
    // =================================================================================

    /**
     * The four-part check that no unit was oversold, double-sold, or leaked.
     *
     * @param expectedSales how many orders should have committed stock for this SKU
     */
    private void assertStockFullyConsumed(long variantId, int expectedSales) {
        List<InventoryItem> items = inventoryItemRepository.findByVariantId(variantId);

        int onHand = items.stream().mapToInt(InventoryItem::getOnHand).sum();
        int reserved = items.stream().mapToInt(InventoryItem::getReserved).sum();

        assertThat(onHand)
                .as("every unit must have physically left the warehouses")
                .isZero();
        assertThat(reserved)
                .as("no reservation may be left holding stock after the race settles")
                .isZero();

        // Per-row invariant: the database CHECK constraint must hold everywhere, not just in aggregate.
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getReserved()).isBetween(0, item.getOnHand());
            assertThat(item.available()).isGreaterThanOrEqualTo(0);
        });

        long committedReservations = reservationRepository.findAll().stream()
                .filter(reservation -> reservation.getVariantId().equals(variantId))
                .filter(reservation -> reservation.getStatus() == ReservationStatus.COMMITTED)
                .count();
        assertThat(committedReservations)
                .as("the reservation ledger must agree with the counters: one commit per sold unit's order")
                .isEqualTo(expectedSales);

        // Counted with a projection query: touching order.getLines() here would need an open session,
        // and the race deliberately runs outside one so it observes genuinely committed state.
        long confirmedOrders = orderRepository.countByStatusContainingVariant(
                OrderStatus.CONFIRMED, variantId);
        assertThat(confirmedOrders)
                .as("exactly %d confirmed order(s) may contain this SKU", expectedSales)
                .isEqualTo(expectedSales);
    }

    private int totalAvailable(long variantId) {
        return inventoryItemRepository.findByVariantId(variantId).stream()
                .mapToInt(InventoryItem::available)
                .sum();
    }

    private int totalReserved(long variantId) {
        return inventoryItemRepository.findByVariantId(variantId).stream()
                .mapToInt(InventoryItem::getReserved)
                .sum();
    }
}
