package com.buyology.buyology_courier.assignment.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox event published when a courier rejects their assignment.
 * Routing key: {@code delivery.courier.assignment.rejected}
 * Exchange:    {@code buyology.delivery.exchange}
 */
public record CourierAssignmentRejectedEvent(
        int eventVersion,
        UUID deliveryId,
        UUID ecommerceOrderId,
        UUID courierId,
        UUID assignmentId,
        int attemptNumber,
        String rejectionReason,
        Instant rejectedAt,
        Instant occurredAt
) {
    public static CourierAssignmentRejectedEvent of(UUID deliveryId, UUID ecommerceOrderId,
                                                     UUID courierId, UUID assignmentId,
                                                     int attemptNumber, String rejectionReason) {
        Instant now = Instant.now();
        return new CourierAssignmentRejectedEvent(1, deliveryId, ecommerceOrderId,
                courierId, assignmentId, attemptNumber, rejectionReason, now, now);
    }
}
