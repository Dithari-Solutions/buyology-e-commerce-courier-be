package com.buyology.buyology_courier.assignment.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox event published when a courier is assigned to a delivery order.
 * Routing key: {@code delivery.courier.assigned}
 * Exchange:    {@code buyology.delivery.exchange}
 */
public record CourierAssignedEvent(
        int eventVersion,
        UUID deliveryId,
        UUID ecommerceOrderId,
        UUID courierId,
        String courierName,
        String courierPhone,
        UUID assignmentId,
        int attemptNumber,
        Instant assignedAt,
        Instant occurredAt
) {
    public static CourierAssignedEvent of(UUID deliveryId, UUID ecommerceOrderId,
                                          UUID courierId, String courierName, String courierPhone,
                                          UUID assignmentId, int attemptNumber) {
        return new CourierAssignedEvent(1, deliveryId, ecommerceOrderId,
                courierId, courierName, courierPhone,
                assignmentId, attemptNumber, Instant.now(), Instant.now());
    }
}
