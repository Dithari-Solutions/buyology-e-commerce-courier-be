package com.buyology.buyology_courier.courier.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record CourierDeletedEvent(
        int eventVersion,
        UUID courierId,
        Instant deletedAt,
        Instant occurredAt
) {
    public static CourierDeletedEvent of(UUID courierId, Instant deletedAt) {
        return new CourierDeletedEvent(1, courierId, deletedAt, Instant.now());
    }
}
