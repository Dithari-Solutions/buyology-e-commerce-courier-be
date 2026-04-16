package com.buyology.buyology_courier.assignment.job;

import com.buyology.buyology_courier.assignment.domain.enums.AssignmentStatus;
import com.buyology.buyology_courier.assignment.repository.CourierAssignmentRepository;
import com.buyology.buyology_courier.assignment.service.CourierAssignmentService;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.repository.DeliveryOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Retries courier assignment for orders that are stuck in {@code CREATED} status.
 *
 * <p>This covers the race condition where no courier was in the Redis GEO index
 * at the moment the order was ingested (e.g. couriers just opened the app).
 * The job runs every 30 seconds and picks up any order older than 20 seconds
 * that still has no courier assigned.
 *
 * <p>20-second grace period avoids re-running the assignment that is already
 * in flight on the async thread pool right after ingest.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaleOrderRetryJob {

    /** Grace period — skip orders younger than this to avoid double-assignment. */
    private static final int GRACE_SECONDS = 20;

    private final DeliveryOrderRepository    deliveryOrderRepository;
    private final CourierAssignmentService   assignmentService;
    private final CourierAssignmentRepository assignmentRepository;

    @Scheduled(fixedDelayString = "${assignment.retry-interval-ms:30000}")
    public void retryStaleOrders() {
        Instant cutoff = Instant.now().minus(GRACE_SECONDS, ChronoUnit.SECONDS);

        List<DeliveryOrder> stale = deliveryOrderRepository
                .findByStatusAndCreatedAtBefore(DeliveryStatus.CREATED, cutoff);

        if (stale.isEmpty()) return;

        log.info("[RetryJob] Found {} order(s) stuck in CREATED — retrying assignment", stale.size());

        for (DeliveryOrder order : stale) {
            try {
                // Load the real attempt count and all couriers who already timed out or rejected
                // so we don't re-offer the same order to a courier who already ignored it.
                int pastAttempts = assignmentRepository.countByDelivery(order);

                Set<UUID> excludedIds = assignmentRepository
                        .findByDeliveryOrderByAttemptNumberDesc(order)
                        .stream()
                        .filter(a -> a.getStatus() == AssignmentStatus.REJECTED
                                  || a.getStatus() == AssignmentStatus.TIMED_OUT)
                        .map(a -> a.getCourier().getId())
                        .collect(Collectors.toSet());

                log.info("[RetryJob] Retrying assignment for deliveryId={} age={}s attempt={} excluded={}",
                        order.getId(),
                        ChronoUnit.SECONDS.between(order.getCreatedAt(), Instant.now()),
                        pastAttempts + 1,
                        excludedIds.size());

                assignmentService.attemptAssignment(order, pastAttempts + 1, excludedIds);
            } catch (Exception ex) {
                log.error("[RetryJob] Assignment retry failed for deliveryId={}: {}",
                        order.getId(), ex.getMessage());
            }
        }
    }
}
