package com.buyology.buyology_courier.courier.messaging.event;

import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;

import java.time.Instant;
import java.util.UUID;

public record CourierStatusChangedEvent(
        int eventVersion,
        UUID courierId,
        CourierStatus previousStatus,
        CourierStatus newStatus,
        Instant occurredAt
) {
    public static CourierStatusChangedEvent of(UUID courierId, CourierStatus previousStatus, CourierStatus newStatus) {
        return new CourierStatusChangedEvent(1, courierId, previousStatus, newStatus, Instant.now());
    }
}
