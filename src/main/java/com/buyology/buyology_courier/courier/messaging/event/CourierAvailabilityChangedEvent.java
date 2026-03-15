package com.buyology.buyology_courier.courier.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record CourierAvailabilityChangedEvent(
        int eventVersion,
        UUID courierId,
        boolean available,
        Instant occurredAt
) {
    public static CourierAvailabilityChangedEvent of(UUID courierId, boolean available) {
        return new CourierAvailabilityChangedEvent(1, courierId, available, Instant.now());
    }
}
