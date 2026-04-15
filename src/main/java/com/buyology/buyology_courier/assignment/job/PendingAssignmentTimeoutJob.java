package com.buyology.buyology_courier.assignment.job;

import com.buyology.buyology_courier.assignment.domain.CourierAssignment;
import com.buyology.buyology_courier.assignment.domain.enums.AssignmentStatus;
import com.buyology.buyology_courier.assignment.repository.CourierAssignmentRepository;
import com.buyology.buyology_courier.assignment.service.event.ReassignApplicationEvent;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.courier.repository.CourierRepository;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.buyology.buyology_courier.delivery.domain.DeliveryStatusHistory;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.repository.DeliveryOrderRepository;
import com.buyology.buyology_courier.delivery.repository.DeliveryStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Expires PENDING courier assignments that have gone unanswered for too long.
 *
 * <p>When a courier is assigned they have {@code assignment.timeout-minutes} (default 2 min)
 * to accept or reject. If they don't respond:
 * <ol>
 *   <li>The assignment is marked {@link AssignmentStatus#TIMED_OUT}.</li>
 *   <li>The delivery order reverts to {@link DeliveryStatus#CREATED} so the retry job
 *       and the stale-order retry job can pick it up again.</li>
 *   <li>The courier's {@code isAvailable} flag is restored to {@code true} so they
 *       re-enter the candidate pool once their app reconnects.</li>
 *   <li>Reassignment is triggered with previously-rejected couriers excluded.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingAssignmentTimeoutJob {

    /** How long a courier has to accept/reject before the assignment is expired. */
    private static final int TIMEOUT_MINUTES = 2;

    private final CourierAssignmentRepository  assignmentRepository;
    private final DeliveryOrderRepository      deliveryOrderRepository;
    private final DeliveryStatusHistoryRepository statusHistoryRepository;
    private final CourierRepository            courierRepository;
    private final ApplicationEventPublisher    eventPublisher;

    @Scheduled(fixedDelayString = "${assignment.timeout-check-interval-ms:30000}")
    @Transactional
    public void expireTimedOutAssignments() {
        Instant cutoff = Instant.now().minus(TIMEOUT_MINUTES, ChronoUnit.MINUTES);

        List<CourierAssignment> stale = assignmentRepository
                .findByStatusAndAssignedAtBefore(AssignmentStatus.PENDING, cutoff);

        if (stale.isEmpty()) return;

        log.info("[TimeoutJob] Found {} PENDING assignment(s) past {} min timeout — expiring",
                stale.size(), TIMEOUT_MINUTES);

        for (CourierAssignment assignment : stale) {
            try {
                expireAssignment(assignment);
            } catch (Exception ex) {
                log.error("[TimeoutJob] Failed to expire assignmentId={}: {}",
                        assignment.getId(), ex.getMessage());
            }
        }
    }

    private void expireAssignment(CourierAssignment assignment) {
        DeliveryOrder order = assignment.getDelivery();

        // Only act if the order is still waiting on this courier.
        // COURIER_ASSIGNED is the expected status; skip anything that already moved on.
        if (order.getStatus() != DeliveryStatus.COURIER_ASSIGNED) {
            log.debug("[TimeoutJob] Skipping assignmentId={} — order already in status={}",
                    assignment.getId(), order.getStatus());
            return;
        }

        log.warn("[TimeoutJob] Expiring assignmentId={} courierId={} deliveryId={} — no response in {}min",
                assignment.getId(), assignment.getCourier().getId(),
                order.getId(), TIMEOUT_MINUTES);

        // 1. Mark assignment as timed out
        assignment.setStatus(AssignmentStatus.TIMED_OUT);
        assignment.setRejectionReason("Courier did not respond within " + TIMEOUT_MINUTES + " minutes");
        assignmentRepository.save(assignment);

        // 2. Restore courier availability — GEO index is refreshed on next location ping
        Courier courier = assignment.getCourier();
        courier.setAvailable(true);
        courierRepository.save(courier);

        // 3. Revert order to CREATED so it re-enters the assignment pool
        order.setAssignedCourier(null);
        order.setStatus(DeliveryStatus.CREATED);
        order.setEstimatedDeliveryTime(null);
        deliveryOrderRepository.save(order);

        statusHistoryRepository.save(DeliveryStatusHistory.builder()
                .delivery(order)
                .status(DeliveryStatus.CREATED)
                .changedBy("SYSTEM")
                .notes("Courier " + courier.getId() + " timed out — reverting to CREATED for reassignment")
                .build());

        // 4. Trigger reassignment, excluding all couriers that already timed out or rejected
        int totalAttempts = assignmentRepository.countByDelivery(order);

        Set<UUID> excludedIds = assignmentRepository
                .findByDeliveryOrderByAttemptNumberDesc(order)
                .stream()
                .filter(a -> a.getStatus() == AssignmentStatus.REJECTED
                          || a.getStatus() == AssignmentStatus.TIMED_OUT)
                .map(a -> a.getCourier().getId())
                .collect(Collectors.toSet());

        eventPublisher.publishEvent(
                new ReassignApplicationEvent(order.getId(), totalAttempts + 1, excludedIds));

        log.info("[TimeoutJob] Reverted deliveryId={} to CREATED, reassignment attempt={}",
                order.getId(), totalAttempts + 1);
    }
}
