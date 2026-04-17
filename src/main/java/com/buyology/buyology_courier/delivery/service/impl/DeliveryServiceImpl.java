package com.buyology.buyology_courier.delivery.service.impl;

import com.buyology.buyology_courier.common.outbox.OutboxEvent;
import com.buyology.buyology_courier.common.outbox.OutboxEventRepository;
import com.buyology.buyology_courier.common.outbox.OutboxStatus;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.buyology.buyology_courier.delivery.domain.DeliveryProof;
import com.buyology.buyology_courier.delivery.domain.DeliveryStatusHistory;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.dto.request.CancelDeliveryRequest;
import com.buyology.buyology_courier.delivery.dto.request.FailDeliveryRequest;
import com.buyology.buyology_courier.delivery.dto.request.UpdateDeliveryStatusRequest;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryOrderResponse;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryProofResponse;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryStatusHistoryResponse;
import com.buyology.buyology_courier.delivery.messaging.config.DeliveryRabbitMQConfig;
import com.buyology.buyology_courier.delivery.messaging.event.DeliveryOrderReceivedEvent;
import com.buyology.buyology_courier.delivery.messaging.event.DeliveryStatusChangedEvent;
import com.buyology.buyology_courier.delivery.repository.DeliveryOrderRepository;
import com.buyology.buyology_courier.delivery.repository.DeliveryProofRepository;
import com.buyology.buyology_courier.delivery.repository.DeliveryStatusHistoryRepository;
import com.buyology.buyology_courier.assignment.service.event.DeliveryCreatedApplicationEvent;
import com.buyology.buyology_courier.common.storage.FileStorageService;
import com.buyology.buyology_courier.delivery.service.DeliveryService;
import com.buyology.buyology_courier.notification.CourierNotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryServiceImpl implements DeliveryService {

    private static final Set<DeliveryStatus> TERMINAL_STATUSES =
            Set.of(DeliveryStatus.DELIVERED, DeliveryStatus.FAILED, DeliveryStatus.CANCELLED);

    /** Statuses where the courier is actively on the move — location is broadcast to ecommerce. */
    private static final Set<DeliveryStatus> IN_PROGRESS_STATUSES = Set.of(
            DeliveryStatus.COURIER_ASSIGNED,
            DeliveryStatus.COURIER_ACCEPTED,
            DeliveryStatus.ARRIVED_AT_PICKUP,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.ON_THE_WAY,
            DeliveryStatus.ARRIVED_AT_DESTINATION
    );

    private final DeliveryOrderRepository         deliveryOrderRepository;
    private final DeliveryStatusHistoryRepository statusHistoryRepository;
    private final DeliveryProofRepository         deliveryProofRepository;
    private final OutboxEventRepository           outboxEventRepository;
    private final ApplicationEventPublisher       eventPublisher;
    private final CourierNotificationService      notificationService;
    private final ObjectMapper                    objectMapper;
    private final FileStorageService              fileStorageService;

    // ── Ingest ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DeliveryOrderResponse ingest(DeliveryOrderReceivedEvent event) {
        log.info("[Delivery] ingest start — ecommerceOrderId={} storeId={} priority={} packageSize={} " +
                        "pickupLat={} pickupLng={} dropoffLat={} dropoffLng={} deliveryFee={}",
                event.ecommerceOrderId(), event.ecommerceStoreId(), event.priority(), event.packageSize(),
                event.pickupLat(), event.pickupLng(), event.dropoffLat(), event.dropoffLng(), event.deliveryFee());

        // Null-field guard — log exactly which required fields are missing
        boolean hasNulls = false;
        if (event.ecommerceOrderId() == null)  { log.error("[Delivery] MISSING ecommerceOrderId"); hasNulls = true; }
        if (event.ecommerceStoreId() == null)  { log.error("[Delivery] MISSING ecommerceStoreId"); hasNulls = true; }
        if (event.pickupLat() == null)         { log.error("[Delivery] MISSING pickupLat"); hasNulls = true; }
        if (event.pickupLng() == null)         { log.error("[Delivery] MISSING pickupLng"); hasNulls = true; }
        if (event.dropoffLat() == null)        { log.error("[Delivery] MISSING dropoffLat"); hasNulls = true; }
        if (event.dropoffLng() == null)        { log.error("[Delivery] MISSING dropoffLng"); hasNulls = true; }
        if (event.priority() == null)          { log.error("[Delivery] MISSING priority"); hasNulls = true; }
        if (hasNulls) {
            throw new IllegalArgumentException("DeliveryOrderReceivedEvent is missing required fields — see logs above");
        }

        // Idempotency guard — ecommerce may re-publish on retry
        if (deliveryOrderRepository.existsByEcommerceOrderId(event.ecommerceOrderId())) {
            log.info("[Delivery] Duplicate ingest skipped for ecommerceOrderId={}",
                    event.ecommerceOrderId());
            return toResponse(deliveryOrderRepository
                    .findByEcommerceOrderId(event.ecommerceOrderId())
                    .orElseThrow());
        }

        log.info("[Delivery] Building DeliveryOrder entity...");
        DeliveryOrder order = DeliveryOrder.builder()
                .ecommerceOrderId(event.ecommerceOrderId())
                .ecommerceStoreId(event.ecommerceStoreId())
                .customerName(event.customerName())
                .customerPhone(event.customerPhone())
                .customerEmail(event.customerEmail())
                .pickupAddress(event.pickupAddress())
                .pickupLat(event.pickupLat())
                .pickupLng(event.pickupLng())
                .dropoffAddress(event.dropoffAddress())
                .dropoffLat(event.dropoffLat())
                .dropoffLng(event.dropoffLng())
                .packageSize(event.packageSize())
                .packageWeight(event.packageWeight())
                .deliveryFee(event.deliveryFee())
                .priority(event.priority())
                .status(DeliveryStatus.CREATED)
                .build();

        log.info("[Delivery] Saving DeliveryOrder to DB...");
        try {
            order = deliveryOrderRepository.save(order);
            log.info("[Delivery] Saved DeliveryOrder id={} for ecommerceOrderId={}", order.getId(), order.getEcommerceOrderId());
        } catch (Exception e) {
            log.error("[Delivery] DB save failed for ecommerceOrderId={} — {}: {}",
                    event.ecommerceOrderId(), e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }

        appendHistory(order, DeliveryStatus.CREATED, null, null, "SYSTEM", null);
        publishStatusEvent(order, "SYSTEM");

        // Trigger async nearest-courier assignment AFTER this transaction commits
        eventPublisher.publishEvent(new DeliveryCreatedApplicationEvent(order.getId()));

        log.info("[Delivery] Ingested deliveryId={} for ecommerceOrderId={}",
                order.getId(), order.getEcommerceOrderId());
        return toResponse(order);
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DeliveryOrderResponse findById(UUID deliveryId) {
        return toResponse(findOrThrow(deliveryId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryOrderResponse> findByStatus(DeliveryStatus status, Pageable pageable) {
        if (status == null) {
            return deliveryOrderRepository.findAll(pageable).map(this::toResponse);
        }
        return deliveryOrderRepository.findByStatus(status, pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryOrderResponse> findAssignedToCourier(UUID courierId, Pageable pageable) {
        return deliveryOrderRepository
                .findByAssignedCourierIdAndStatusNotIn(courierId, TERMINAL_STATUSES, pageable)
                .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryOrderResponse> findAllByCourier(UUID courierId, DeliveryStatus status, Pageable pageable) {
        if (status == null) {
            return deliveryOrderRepository.findByAssignedCourierId(courierId, pageable).map(this::toResponse);
        }
        return deliveryOrderRepository.findByAssignedCourierIdAndStatus(courierId, status, pageable)
                .map(this::toResponse);
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DeliveryOrderResponse updateStatus(UUID deliveryId, UUID courierId,
                                              UpdateDeliveryStatusRequest request) {
        DeliveryOrder order = findOrThrow(deliveryId);

        validateCourierOwnsDelivery(order, courierId);
        validateNotTerminal(order);
        validateStatusTransition(order.getStatus(), request.status());

        order.setStatus(request.status());
        if (request.status() == DeliveryStatus.DELIVERED) {
            order.setActualDeliveryTime(Instant.now());
        }

        appendHistory(order, request.status(),
                request.latitude(), request.longitude(),
                "COURIER", request.notes());
        publishStatusEvent(order, "COURIER");

        log.info("[Delivery] Status updated deliveryId={} status={} by courierId={}",
                deliveryId, request.status(), courierId);
        return toResponse(order);
    }

    @Override
    @Transactional
    public DeliveryOrderResponse cancel(UUID deliveryId, CancelDeliveryRequest request) {
        DeliveryOrder order = findOrThrow(deliveryId);
        validateNotTerminal(order);

        order.setStatus(DeliveryStatus.CANCELLED);
        order.setCancelledReason(request.reason());

        appendHistory(order, DeliveryStatus.CANCELLED, null, null, "SYSTEM", request.reason());
        publishStatusEvent(order, "SYSTEM");

        // Notify the assigned courier (if any) via FCM push + email so they stop the job immediately
        notificationService.notifyCourierCancelled(order, request.reason());

        log.info("[Delivery] Cancelled deliveryId={} reason='{}'", deliveryId, request.reason());
        return toResponse(order);
    }

    /**
     * Saves the pickup proof photo and advances status ARRIVED_AT_PICKUP → PICKED_UP.
     * Creates a DeliveryProof row if one doesn't exist yet, otherwise updates it.
     */
    @Override
    @Transactional
    public DeliveryProofResponse submitPickupProof(UUID deliveryId, UUID courierId,
                                                   String imageUrl, Instant photoTakenAt) {
        DeliveryOrder order = findOrThrow(deliveryId);
        validateCourierOwnsDelivery(order, courierId);

        if (order.getStatus() != DeliveryStatus.ARRIVED_AT_PICKUP) {
            throw new IllegalStateException(
                    "Pickup proof can only be submitted when status is ARRIVED_AT_PICKUP. Current: "
                            + order.getStatus());
        }

        DeliveryProof proof = deliveryProofRepository.findByDeliveryId(deliveryId)
                .orElseGet(() -> DeliveryProof.builder().delivery(order).build());
        proof.setPickupImageUrl(imageUrl);
        proof.setPickupPhotoTakenAt(photoTakenAt != null ? photoTakenAt : Instant.now());
        deliveryProofRepository.save(proof);

        order.setStatus(DeliveryStatus.PICKED_UP);
        appendHistory(order, DeliveryStatus.PICKED_UP, null, null, "COURIER",
                "Pickup proof submitted");
        publishStatusEvent(order, "COURIER");

        log.info("[Delivery] Pickup proof submitted deliveryId={} courierId={}", deliveryId, courierId);
        return toProofResponse(proof);
    }

    /**
     * Saves the delivery proof photo and advances status ARRIVED_AT_DESTINATION → DELIVERED.
     */
    @Override
    @Transactional
    public DeliveryProofResponse submitDeliveryProof(UUID deliveryId, UUID courierId,
                                                     String imageUrl, String deliveredTo,
                                                     Instant photoTakenAt) {
        DeliveryOrder order = findOrThrow(deliveryId);
        validateCourierOwnsDelivery(order, courierId);

        if (order.getStatus() != DeliveryStatus.ARRIVED_AT_DESTINATION) {
            throw new IllegalStateException(
                    "Delivery proof can only be submitted when status is ARRIVED_AT_DESTINATION. Current: "
                            + order.getStatus());
        }

        DeliveryProof proof = deliveryProofRepository.findByDeliveryId(deliveryId)
                .orElseGet(() -> DeliveryProof.builder().delivery(order).build());
        proof.setImageUrl(imageUrl);
        proof.setDeliveredTo(deliveredTo);
        proof.setPhotoTakenAt(photoTakenAt != null ? photoTakenAt : Instant.now());
        deliveryProofRepository.save(proof);

        order.setStatus(DeliveryStatus.DELIVERED);
        order.setActualDeliveryTime(Instant.now());
        appendHistory(order, DeliveryStatus.DELIVERED, null, null, "COURIER",
                "Delivery proof submitted" + (deliveredTo != null ? " — received by: " + deliveredTo : ""));
        publishStatusEvent(order, "COURIER");

        // Notify the customer asynchronously — runs on eventPublisherExecutor, never blocks the transaction
        notificationService.notifyCustomerDelivered(order);
        // Notify the courier (FCM push + email) that the delivery is complete
        notificationService.notifyCourierDelivered(order);
        // Prompt the customer to rate the delivery and products
        notificationService.notifyCustomerRatingRequest(order);

        log.info("[Delivery] Delivery proof submitted deliveryId={} courierId={}", deliveryId, courierId);
        return toProofResponse(proof);
    }

    /**
     * Marks a delivery as FAILED. Allowed from any in-progress status.
     */
    @Override
    @Transactional
    public DeliveryOrderResponse failDelivery(UUID deliveryId, UUID courierId,
                                              FailDeliveryRequest request) {
        DeliveryOrder order = findOrThrow(deliveryId);
        validateCourierOwnsDelivery(order, courierId);
        validateNotTerminal(order);

        if (!IN_PROGRESS_STATUSES.contains(order.getStatus())) {
            throw new IllegalStateException(
                    "Delivery cannot be failed from status: " + order.getStatus());
        }

        order.setStatus(DeliveryStatus.FAILED);
        order.setCancelledReason(request.reason());
        appendHistory(order, DeliveryStatus.FAILED,
                request.latitude(), request.longitude(),
                "COURIER", "Failed: " + request.reason());
        publishStatusEvent(order, "COURIER");

        // Notify the customer with the courier-provided failure reason — runs async
        notificationService.notifyCustomerFailed(order, request.reason());

        log.info("[Delivery] Marked as FAILED deliveryId={} courierId={} reason='{}'",
                deliveryId, courierId, request.reason());
        return toResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryProofResponse getProof(UUID deliveryId) {
        findOrThrow(deliveryId);
        DeliveryProof proof = deliveryProofRepository.findByDeliveryId(deliveryId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "No proof found for delivery: " + deliveryId));
        return toProofResponse(proof);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryStatusHistoryResponse> getStatusHistory(UUID deliveryId) {
        findOrThrow(deliveryId); // existence check
        return statusHistoryRepository
                .findByDeliveryIdOrderByCreatedAtAsc(deliveryId)
                .stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DeliveryOrder findOrThrow(UUID id) {
        return deliveryOrderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "DeliveryOrder not found: " + id));
    }

    private void validateCourierOwnsDelivery(DeliveryOrder order, UUID courierId) {
        if (order.getAssignedCourier() == null
                || !courierId.equals(order.getAssignedCourier().getId())) {
            throw new IllegalStateException(
                    "Courier " + courierId + " is not assigned to delivery " + order.getId());
        }
    }

    private void validateNotTerminal(DeliveryOrder order) {
        if (TERMINAL_STATUSES.contains(order.getStatus())) {
            throw new IllegalStateException(
                    "Delivery " + order.getId() + " is already in terminal status: "
                            + order.getStatus());
        }
    }

    /**
     * Enforces the allowed forward status transitions a courier can trigger.
     * Admin / system cancel bypasses this via {@link #cancel}.
     */
    private void validateStatusTransition(DeliveryStatus current, DeliveryStatus next) {
        boolean valid = switch (current) {
            case COURIER_ASSIGNED       -> next == DeliveryStatus.COURIER_ACCEPTED
                                        || next == DeliveryStatus.CANCELLED;
            case COURIER_ACCEPTED       -> next == DeliveryStatus.ARRIVED_AT_PICKUP;
            case ARRIVED_AT_PICKUP      -> next == DeliveryStatus.PICKED_UP;
            case PICKED_UP              -> next == DeliveryStatus.ON_THE_WAY;
            case ON_THE_WAY             -> next == DeliveryStatus.ARRIVED_AT_DESTINATION;
            case ARRIVED_AT_DESTINATION -> next == DeliveryStatus.DELIVERED;
            default -> false;
        };
        if (!valid) {
            throw new IllegalStateException(
                    "Invalid status transition: " + current + " → " + next);
        }
    }

    private void appendHistory(DeliveryOrder order, DeliveryStatus status,
                               java.math.BigDecimal lat, java.math.BigDecimal lng,
                               String changedBy, String notes) {
        statusHistoryRepository.save(DeliveryStatusHistory.builder()
                .delivery(order)
                .status(status)
                .latitude(lat)
                .longitude(lng)
                .changedBy(changedBy)
                .notes(notes)
                .build());
    }

    private void publishStatusEvent(DeliveryOrder order, String changedBy) {
        UUID courierId = order.getAssignedCourier() != null
                ? order.getAssignedCourier().getId() : null;

        DeliveryStatusChangedEvent event = new DeliveryStatusChangedEvent(
                order.getId(),
                order.getEcommerceOrderId(),
                order.getStatus(),
                courierId,
                changedBy,
                Instant.now()
        );

        String routingKey = switch (order.getStatus()) {
            case DELIVERED -> DeliveryRabbitMQConfig.DELIVERY_COMPLETED_KEY;
            case CANCELLED, FAILED -> DeliveryRabbitMQConfig.DELIVERY_CANCELLED_KEY;
            default        -> DeliveryRabbitMQConfig.DELIVERY_STATUS_CHANGED_KEY;
        };

        saveOutboxEvent(DeliveryRabbitMQConfig.DELIVERY_EXCHANGE, routingKey, event);
    }

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

    // ── Mappers ───────────────────────────────────────────────────────────────

    private DeliveryOrderResponse toResponse(DeliveryOrder o) {
        return new DeliveryOrderResponse(
                o.getId(),
                o.getEcommerceOrderId(),
                o.getEcommerceStoreId(),
                o.getCustomerName(),
                o.getCustomerPhone(),
                o.getCustomerEmail(),
                o.getPickupAddress(),
                o.getPickupLat(),
                o.getPickupLng(),
                o.getDropoffAddress(),
                o.getDropoffLat(),
                o.getDropoffLng(),
                o.getPackageSize(),
                o.getPackageWeight(),
                o.getDeliveryFee(),
                o.getPriority(),
                o.getStatus(),
                o.getAssignedCourier() != null ? o.getAssignedCourier().getId() : null,
                o.getEstimatedDeliveryTime(),
                o.getActualDeliveryTime(),
                o.getCancelledReason(),
                o.getCreatedAt(),
                o.getUpdatedAt()
        );
    }

    private DeliveryStatusHistoryResponse toHistoryResponse(DeliveryStatusHistory h) {
        return new DeliveryStatusHistoryResponse(
                h.getId(),
                h.getStatus(),
                h.getLatitude(),
                h.getLongitude(),
                h.getChangedBy(),
                h.getNotes(),
                h.getCreatedAt()
        );
    }

    private DeliveryProofResponse toProofResponse(DeliveryProof p) {
        return new DeliveryProofResponse(
                p.getId(),
                p.getDelivery().getId(),
                fileStorageService.getPresignedUrl(p.getPickupImageUrl()),
                p.getPickupPhotoTakenAt(),
                fileStorageService.getPresignedUrl(p.getImageUrl()),
                fileStorageService.getPresignedUrl(p.getSignatureUrl()),
                p.getDeliveredTo(),
                p.getPhotoTakenAt(),
                p.getCreatedAt()
        );
    }
}
