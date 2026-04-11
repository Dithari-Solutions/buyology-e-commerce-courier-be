package com.buyology.buyology_courier.assignment.service.impl;

import com.buyology.buyology_courier.assignment.domain.CourierAssignment;
import com.buyology.buyology_courier.assignment.domain.enums.AssignmentStatus;
import com.buyology.buyology_courier.assignment.dto.enums.AssignmentAction;
import com.buyology.buyology_courier.assignment.dto.request.AssignmentRespondRequest;
import com.buyology.buyology_courier.assignment.dto.response.AssignmentResponse;
import com.buyology.buyology_courier.assignment.exception.AssignmentAlreadyRespondedException;
import com.buyology.buyology_courier.assignment.exception.AssignmentNotFoundException;
import com.buyology.buyology_courier.assignment.messaging.event.AssignmentExhaustedEvent;
import com.buyology.buyology_courier.assignment.messaging.event.CourierAssignedEvent;
import com.buyology.buyology_courier.assignment.messaging.event.CourierAssignmentAcceptedEvent;
import com.buyology.buyology_courier.assignment.messaging.event.CourierAssignmentRejectedEvent;
import com.buyology.buyology_courier.assignment.repository.CourierAssignmentRepository;
import com.buyology.buyology_courier.assignment.service.CourierAssignmentService;
import com.buyology.buyology_courier.assignment.service.CourierGeoService;
import com.buyology.buyology_courier.assignment.service.event.CourierAssignedApplicationEvent;
import com.buyology.buyology_courier.assignment.service.event.DeliveryCreatedApplicationEvent;
import com.buyology.buyology_courier.assignment.service.event.ReassignApplicationEvent;
import com.buyology.buyology_courier.assignment.util.HaversineUtil;
import com.buyology.buyology_courier.common.outbox.OutboxEvent;
import com.buyology.buyology_courier.common.outbox.OutboxEventRepository;
import com.buyology.buyology_courier.common.outbox.OutboxStatus;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.repository.CourierRepository;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.buyology.buyology_courier.delivery.domain.DeliveryStatusHistory;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.messaging.config.DeliveryRabbitMQConfig;
import com.buyology.buyology_courier.delivery.messaging.event.DeliveryStatusChangedEvent;
import com.buyology.buyology_courier.delivery.repository.DeliveryOrderRepository;
import com.buyology.buyology_courier.delivery.repository.DeliveryStatusHistoryRepository;
import com.buyology.buyology_courier.notification.CourierNotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CourierAssignmentServiceImpl implements CourierAssignmentService {

    /** Max assignment retries per delivery before giving up and alerting admins. */
    private static final int MAX_ASSIGNMENT_ATTEMPTS = 3;

    /**
     * Radius tiers searched in order (km).
     * At 50 km the 30-minute delivery constraint is relaxed — long-distance fallback.
     */
    private static final double[] RADIUS_TIERS_KM = {5.0, 15.0, 50.0};
    private static final double LONG_DISTANCE_RADIUS_KM = 50.0;
    private static final double MAX_DELIVERY_MINUTES = 30.0;

    private final DeliveryOrderRepository          deliveryOrderRepository;
    private final DeliveryStatusHistoryRepository  statusHistoryRepository;
    private final CourierAssignmentRepository      assignmentRepository;
    private final CourierRepository                courierRepository;
    private final CourierGeoService                courierGeoService;
    private final OutboxEventRepository            outboxEventRepository;
    private final ApplicationEventPublisher        eventPublisher;
    private final ObjectMapper                     objectMapper;
    private final CourierNotificationService       notificationService;

    // ── Entry points (Spring application event listeners) ────────────────────

    /**
     * Triggered after a delivery order's ingest transaction commits.
     * Runs on the async thread pool to avoid blocking the RabbitMQ consumer thread.
     */
    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("eventPublisherExecutor")
    public void onDeliveryCreated(DeliveryCreatedApplicationEvent event) {
        log.info("[Assignment] onDeliveryCreated deliveryId={}", event.deliveryId());
        DeliveryOrder order = deliveryOrderRepository.findById(event.deliveryId()).orElse(null);
        if (order == null) {
            log.warn("[Assignment] DeliveryOrder not found for deliveryId={}", event.deliveryId());
            return;
        }
        if (order.getStatus() != DeliveryStatus.CREATED) {
            log.info("[Assignment] Skipping assignment — deliveryId={} already in status={}",
                    event.deliveryId(), order.getStatus());
            return;
        }
        attemptAssignment(order, 1, Collections.emptySet());
    }

    /**
     * Triggered after a rejection transaction commits (when attempts < max).
     * Re-runs the nearest-courier search while excluding previously rejected couriers.
     */
    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("eventPublisherExecutor")
    public void onReassignRequested(ReassignApplicationEvent event) {
        log.info("[Assignment] onReassignRequested deliveryId={} attempt={}",
                event.deliveryId(), event.nextAttemptNumber());
        DeliveryOrder order = deliveryOrderRepository.findById(event.deliveryId()).orElse(null);
        if (order == null) {
            log.warn("[Assignment] DeliveryOrder not found on reassign deliveryId={}", event.deliveryId());
            return;
        }
        attemptAssignment(order, event.nextAttemptNumber(), event.excludedCourierIds());
    }

    // ── Core assignment logic ─────────────────────────────────────────────────

    /**
     * Searches for the nearest eligible courier and creates a PENDING assignment.
     * Runs in its own transaction (REQUIRES_NEW) so the assignment, order update,
     * and outbox events all commit atomically, independent of the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attemptAssignment(DeliveryOrder order, int attemptNumber, Set<UUID> excludedCourierIds) {
        double pickupLat = order.getPickupLat().doubleValue();
        double pickupLng = order.getPickupLng().doubleValue();
        double dropoffLat = order.getDropoffLat().doubleValue();
        double dropoffLng = order.getDropoffLng().doubleValue();

        Courier selectedCourier = null;
        double estimatedMinutes = MAX_DELIVERY_MINUTES;

        for (double radius : RADIUS_TIERS_KM) {
            boolean isLongDistance = radius >= LONG_DISTANCE_RADIUS_KM;

            List<UUID> nearbyIds = courierGeoService.findNearby(pickupLat, pickupLng, radius);
            if (nearbyIds.isEmpty()) continue;

            // Remove couriers who already rejected for this delivery
            List<UUID> candidateIds = nearbyIds.stream()
                    .filter(id -> !excludedCourierIds.contains(id))
                    .toList();
            if (candidateIds.isEmpty()) continue;

            // DB authoritative filter: ACTIVE + available + not deleted
            List<Courier> dbCandidates = courierRepository
                    .findByIdInAndStatusAndIsAvailableTrueAndDeletedAtIsNull(
                            candidateIds, CourierStatus.ACTIVE);

            if (dbCandidates.isEmpty()) continue;

            // Preserve GEO proximity order using a lookup map
            Map<UUID, Courier> courierMap = dbCandidates.stream()
                    .collect(Collectors.toMap(Courier::getId, c -> c));

            // Walk candidates in GEO distance order (nearest first)
            for (UUID candidateId : candidateIds) {
                Courier courier = courierMap.get(candidateId);
                if (courier == null) continue; // not in DB filter result

                double speed = courier.getVehicleType().speedKmh();

                // We need the courier's latest known position; use a placeholder
                // lat/lng from the GEO index — for time estimation we approximate
                // using straight-line distance already sorted by GEO.
                // Exact courier lat/lng is not exposed by findNearby; we estimate
                // total trip as: (courier→pickup estimated by radius tier midpoint) + (pickup→dropoff).
                // For the first tier (5 km) we use an upper-bound of 5 km for courier leg.
                double courierToPickupEstimate = radius; // conservative upper bound
                double pickupToDropoff = HaversineUtil.distanceKm(pickupLat, pickupLng, dropoffLat, dropoffLng);
                double totalEstimatedMinutes = HaversineUtil.travelTimeMinutes(
                        courierToPickupEstimate + pickupToDropoff, speed);

                if (!isLongDistance && totalEstimatedMinutes > MAX_DELIVERY_MINUTES) {
                    continue; // over 30 min — skip
                }

                selectedCourier = courier;
                estimatedMinutes = totalEstimatedMinutes;
                break;
            }

            if (selectedCourier != null) break;
        }

        if (selectedCourier == null) {
            log.warn("[Assignment] No eligible courier found for deliveryId={} attempt={}",
                    order.getId(), attemptNumber);
            // Publish exhausted event if this was the last attempt
            if (attemptNumber >= MAX_ASSIGNMENT_ATTEMPTS) {
                saveOutboxEvent(DeliveryRabbitMQConfig.DELIVERY_EXCHANGE,
                        DeliveryRabbitMQConfig.ASSIGNMENT_EXHAUSTED_KEY,
                        AssignmentExhaustedEvent.of(order.getId(), order.getEcommerceOrderId(), attemptNumber));
                log.error("[Assignment] EXHAUSTED deliveryId={} after {} attempts", order.getId(), attemptNumber);
            }
            return;
        }

        // Re-load delivery order fresh within this REQUIRES_NEW transaction
        DeliveryOrder freshOrder = deliveryOrderRepository.findById(order.getId()).orElse(null);
        if (freshOrder == null) return;

        // Idempotency: only proceed if still in CREATED status.
        // If it's already COURIER_ASSIGNED, another assignment attempt is in flight or won.
        if (freshOrder.getStatus() != DeliveryStatus.CREATED) {
            log.info("[Assignment] Skipping — deliveryId={} status={} (expected CREATED)",
                    freshOrder.getId(), freshOrder.getStatus());
            return;
        }

        // Create the assignment
        CourierAssignment assignment = CourierAssignment.builder()
                .delivery(freshOrder)
                .courier(selectedCourier)
                .status(AssignmentStatus.PENDING)
                .attemptNumber(attemptNumber)
                .assignedAt(Instant.now())
                .build();
        assignment = assignmentRepository.save(assignment);

        // Update delivery order
        freshOrder.setAssignedCourier(selectedCourier);
        freshOrder.setStatus(DeliveryStatus.COURIER_ASSIGNED);
        freshOrder.setEstimatedDeliveryTime(Instant.now().plusSeconds((long)(estimatedMinutes * 60)));
        deliveryOrderRepository.save(freshOrder);

        // Status history
        statusHistoryRepository.save(DeliveryStatusHistory.builder()
                .delivery(freshOrder)
                .status(DeliveryStatus.COURIER_ASSIGNED)
                .changedBy("SYSTEM")
                .notes("Auto-assigned to courier " + selectedCourier.getId() + " (attempt " + attemptNumber + ")")
                .build());

        // Outbox: assignment event for courier app / ecommerce
        saveOutboxEvent(DeliveryRabbitMQConfig.DELIVERY_EXCHANGE,
                DeliveryRabbitMQConfig.COURIER_ASSIGNED_KEY,
                CourierAssignedEvent.of(freshOrder.getId(), freshOrder.getEcommerceOrderId(),
                        selectedCourier.getId(), assignment.getId(), attemptNumber));

        // Outbox: generic status changed event for ecommerce backend sync
        saveOutboxEvent(DeliveryRabbitMQConfig.DELIVERY_EXCHANGE,
                DeliveryRabbitMQConfig.DELIVERY_STATUS_CHANGED_KEY,
                new DeliveryStatusChangedEvent(
                        freshOrder.getId(), freshOrder.getEcommerceOrderId(),
                        DeliveryStatus.COURIER_ASSIGNED, selectedCourier.getId(), "SYSTEM", Instant.now()));

        // Notify the courier: WebSocket push (shows "New order available" in mobile app)
        // + email (fallback for couriers not currently connected).
        // Triggered async AFTER this transaction commits via CourierAssignedApplicationEvent.
        eventPublisher.publishEvent(CourierAssignedApplicationEvent.of(selectedCourier, assignment, freshOrder));

        log.info("[Assignment] Assigned courierId={} to deliveryId={} attempt={}",
                selectedCourier.getId(), freshOrder.getId(), attemptNumber);
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    /**
     * Sends push notifications and emails to the courier after an assignment
     * transaction has committed. Runs on the async thread pool.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("eventPublisherExecutor")
    public void onCourierAssigned(CourierAssignedApplicationEvent event) {
        log.info("[Assignment] onCourierAssigned — triggering notifications for courierId={} assignmentId={}",
                event.courier().getId(), event.assignment().getId());
        notificationService.notifyNewAssignment(event.courier(), event.assignment(), event.order());
    }

    // ── Accept / Reject ───────────────────────────────────────────────────────

    @Override
    @Transactional
    public AssignmentResponse respondToAssignment(UUID assignmentId, UUID courierId,
                                                   AssignmentRespondRequest request) {
        CourierAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new AssignmentNotFoundException(assignmentId));

        // Security: ensure the authenticated courier owns this assignment
        if (!courierId.equals(assignment.getCourier().getId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You are not assigned to this delivery.");
        }

        if (assignment.getStatus() != AssignmentStatus.PENDING) {
            throw new AssignmentAlreadyRespondedException(assignmentId, assignment.getStatus());
        }

        if (request.action() == AssignmentAction.ACCEPT) {
            return handleAccept(assignment);
        } else {
            return handleReject(assignment, request.rejectionReason());
        }
    }

    private AssignmentResponse handleAccept(CourierAssignment assignment) {
        assignment.setStatus(AssignmentStatus.ACCEPTED);
        assignment.setAcceptedAt(Instant.now());
        assignmentRepository.save(assignment);

        DeliveryOrder order = assignment.getDelivery();
        order.setStatus(DeliveryStatus.COURIER_ACCEPTED);
        deliveryOrderRepository.save(order);

        statusHistoryRepository.save(DeliveryStatusHistory.builder()
                .delivery(order)
                .status(DeliveryStatus.COURIER_ACCEPTED)
                .changedBy("COURIER")
                .notes("Courier accepted the assignment")
                .build());

        saveOutboxEvent(DeliveryRabbitMQConfig.DELIVERY_EXCHANGE,
                DeliveryRabbitMQConfig.ASSIGNMENT_ACCEPTED_KEY,
                CourierAssignmentAcceptedEvent.of(order.getId(), order.getEcommerceOrderId(),
                        assignment.getCourier().getId(), assignment.getId()));

        saveOutboxEvent(DeliveryRabbitMQConfig.DELIVERY_EXCHANGE,
                DeliveryRabbitMQConfig.DELIVERY_STATUS_CHANGED_KEY,
                new DeliveryStatusChangedEvent(
                        order.getId(), order.getEcommerceOrderId(),
                        DeliveryStatus.COURIER_ACCEPTED, assignment.getCourier().getId(),
                        "COURIER", Instant.now()));

        log.info("[Assignment] Accepted assignmentId={} by courierId={}",
                assignment.getId(), assignment.getCourier().getId());
        return toResponse(assignment);
    }

    private AssignmentResponse handleReject(CourierAssignment assignment, String reason) {
        assignment.setStatus(AssignmentStatus.REJECTED);
        assignment.setRejectedAt(Instant.now());
        assignment.setRejectionReason(reason);
        assignmentRepository.save(assignment);

        DeliveryOrder order = assignment.getDelivery();

        // Clear the current courier from the order entity so it's "unassigned"
        // while we wait for the next attempt or if we exhaust all attempts.
        order.setAssignedCourier(null);

        saveOutboxEvent(DeliveryRabbitMQConfig.DELIVERY_EXCHANGE,
                DeliveryRabbitMQConfig.ASSIGNMENT_REJECTED_KEY,
                CourierAssignmentRejectedEvent.of(order.getId(), order.getEcommerceOrderId(),
                        assignment.getCourier().getId(), assignment.getId(),
                        assignment.getAttemptNumber(), reason));

        int totalAttempts = assignmentRepository.countByDelivery(order);

        if (totalAttempts < MAX_ASSIGNMENT_ATTEMPTS) {
            // Revert status to CREATED so it's eligible for reassignment logic
            order.setStatus(DeliveryStatus.CREATED);
            deliveryOrderRepository.save(order);

            // Collect all rejected courier IDs for this delivery
            Set<UUID> excludedIds = assignmentRepository
                    .findByDeliveryOrderByAttemptNumberDesc(order)
                    .stream()
                    .filter(a -> a.getStatus() == AssignmentStatus.REJECTED)
                    .map(a -> a.getCourier().getId())
                    .collect(Collectors.toSet());

            // Trigger reassignment AFTER this transaction commits
            eventPublisher.publishEvent(
                    new ReassignApplicationEvent(order.getId(), totalAttempts + 1, excludedIds));

            log.info("[Assignment] Rejected assignmentId={} by courierId={}, triggering reassignment attempt={}",
                    assignment.getId(), assignment.getCourier().getId(), totalAttempts + 1);
        } else {
            // All attempts exhausted — mark order as FAILED and alert admins
            order.setStatus(DeliveryStatus.FAILED);
            deliveryOrderRepository.save(order);

            saveOutboxEvent(DeliveryRabbitMQConfig.DELIVERY_EXCHANGE,
                    DeliveryRabbitMQConfig.ASSIGNMENT_EXHAUSTED_KEY,
                    AssignmentExhaustedEvent.of(order.getId(), order.getEcommerceOrderId(), totalAttempts));
            log.error("[Assignment] EXHAUSTED deliveryId={} after {} attempts", order.getId(), totalAttempts);
        }

        statusHistoryRepository.save(DeliveryStatusHistory.builder()
                .delivery(order)
                .status(order.getStatus())
                .changedBy("SYSTEM")
                .notes("Courier " + assignment.getCourier().getId() + " rejected: " + reason)
                .build());

        return toResponse(assignment);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AssignmentResponse getAssignment(UUID assignmentId, UUID courierId) {
        CourierAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new AssignmentNotFoundException(assignmentId));

        if (!courierId.equals(assignment.getCourier().getId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You are not assigned to this delivery.");
        }

        return toResponse(assignment);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveOutboxEvent(String exchange, String routingKey, Object event) {
        try {
            outboxEventRepository.save(OutboxEvent.builder()
                    .exchange(exchange)
                    .routingKey(routingKey)
                    .payload(objectMapper.writeValueAsString(event))
                    .eventVersion(1)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build());
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(
                    "Failed to serialise outbox event for routing key: " + routingKey, ex);
        }
    }

    private AssignmentResponse toResponse(CourierAssignment a) {
        DeliveryOrder order = a.getDelivery();
        return new AssignmentResponse(
                a.getId(),
                order.getId(),
                a.getCourier().getId(),
                a.getStatus(),
                a.getAttemptNumber(),
                a.getAssignedAt(),
                a.getAcceptedAt(),
                a.getRejectedAt(),
                a.getRejectionReason(),
                a.getCreatedAt(),
                order.getPickupAddress(),
                order.getPickupLat(),
                order.getPickupLng(),
                order.getDropoffAddress(),
                order.getDropoffLat(),
                order.getDropoffLng()
        );
    }
}
