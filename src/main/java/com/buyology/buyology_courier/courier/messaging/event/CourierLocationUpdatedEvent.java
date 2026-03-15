package com.buyology.buyology_courier.courier.messaging.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Published on every GPS ping — consumed by dispatch/assignment service
// to find nearest available couriers for new delivery orders
public record CourierLocationUpdatedEvent(
        int eventVersion,
        UUID courierId,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal heading,
        BigDecimal speed,
        Instant recordedAt,
        Instant occurredAt
) {
    public static CourierLocationUpdatedEvent of(UUID courierId, BigDecimal latitude, BigDecimal longitude,
                                                 BigDecimal heading, BigDecimal speed, Instant recordedAt) {
        return new CourierLocationUpdatedEvent(1, courierId, latitude, longitude, heading, speed, recordedAt, Instant.now());
    }
}
