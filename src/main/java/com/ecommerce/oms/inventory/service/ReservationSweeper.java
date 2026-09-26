package com.ecommerce.oms.inventory.service;

import com.ecommerce.oms.inventory.domain.StockReservation;
import com.ecommerce.oms.inventory.event.ReservationExpiredEvent;
import com.ecommerce.oms.inventory.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The safety net that makes every mid-flight checkout failure recoverable.
 *
 * <p>This job is the reason checkout is allowed to reserve stock, then call a payment gateway
 * outside any transaction, and only then settle. If the process dies in that window — or the
 * gateway never answers — nothing rolls the hold back, because there is no transaction left to
 * roll back. Without a sweeper those units would be unsellable forever, so the reservation TTL
 * plus this job is what converts "unbounded stock leak" into "stock returns within the TTL".
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>Paged.</b> A backlog is drained a batch at a time so one sweep cannot lock thousands
 *       of inventory rows in a single transaction and stall live checkouts.</li>
 *   <li><b>Grouped by reference.</b> All holds for one order are released together and produce
 *       one event, so the order module cancels the order once rather than per line.</li>
 *   <li><b>Never throws.</b> A scheduled method that propagates an exception stops being
 *       rescheduled in some configurations; the failure is logged and the next run retries.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationSweeper {

    /** Holds released per run. Bounded to keep any single sweep short. */
    private static final int BATCH_SIZE = 200;

    private final StockReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelayString = "${oms.inventory.sweeper-interval-ms}", initialDelay = 5_000)
    public void sweepExpiredReservations() {
        try {
            int released = sweepBatch();
            if (released > 0) {
                log.info("Reservation sweep released {} expired hold(s)", released);
            }
        } catch (Exception failure) {
            // Swallowed on purpose: the next scheduled run retries, and a propagated
            // exception here can silently kill the schedule.
            log.error("Reservation sweep failed; will retry on the next run", failure);
        }
    }

    /**
     * Releases one batch of expired holds and announces each affected order.
     *
     * <p>{@code REQUIRES_NEW} because this runs on a scheduler thread with no ambient
     * transaction, and each batch must commit independently of the next.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sweepBatch() {
        Instant now = Instant.now();
        List<StockReservation> expired =
                reservationRepository.findExpired(now, PageRequest.of(0, BATCH_SIZE));

        if (expired.isEmpty()) {
            return 0;
        }

        log.debug("Found {} expired reservation(s) at {}", expired.size(), now);

        Map<String, List<StockReservation>> byReference = expired.stream()
                .collect(Collectors.groupingBy(StockReservation::getReference));

        int released = 0;
        for (Map.Entry<String, List<StockReservation>> group : byReference.entrySet()) {
            String reference = group.getKey();
            List<Long> ids = group.getValue().stream().map(StockReservation::getId).toList();
            Long orderId = group.getValue().stream()
                    .map(StockReservation::getOrderId)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            reservationService.expire(ids);
            released += ids.size();

            log.info("Expired {} hold(s) for reference={} orderId={}", ids.size(), reference, orderId);
            eventPublisher.publishEvent(new ReservationExpiredEvent(reference, orderId, ids, now));
        }
        return released;
    }
}
