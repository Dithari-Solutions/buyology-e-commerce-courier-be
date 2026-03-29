package com.buyology.buyology_courier.assignment.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox event published when a courier accepts their assignment.
 * Routing key: {@code delivery.courier.assignment.accepted}
 * Exchange:    {@code buyology.delivery.exchange}
 */
public record CourierAssignmentAcceptedEvent(
        int eventVersion,
        UUID deliveryId,
        UUID ecommerceOrderId,
        UUID courierId,
        UUID assignmentId,
        Instant acceptedAt,
        Instant occurredAt
) {
    public static CourierAssignmentAcceptedEvent of(UUID deliveryId, UUID ecommerceOrderId,
                                                     UUID courierId, UUID assignmentId) {
        Instant now = Instant.now();
        return new CourierAssignmentAcceptedEvent(1, deliveryId, ecommerceOrderId,
                courierId, assignmentId, now, now);
    }
}
