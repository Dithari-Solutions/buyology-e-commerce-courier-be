package com.buyology.buyology_courier.assignment.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox event published when all assignment attempts are exhausted (no courier accepted).
 * Consumed by the admin/ops service to trigger manual intervention.
 * Routing key: {@code delivery.assignment.exhausted}
 * Exchange:    {@code buyology.delivery.exchange}
 */
public record AssignmentExhaustedEvent(
        int eventVersion,
        UUID deliveryId,
        UUID ecommerceOrderId,
        int totalAttempts,
        Instant occurredAt
) {
    public static AssignmentExhaustedEvent of(UUID deliveryId, UUID ecommerceOrderId, int totalAttempts) {
        return new AssignmentExhaustedEvent(1, deliveryId, ecommerceOrderId, totalAttempts, Instant.now());
    }
}
