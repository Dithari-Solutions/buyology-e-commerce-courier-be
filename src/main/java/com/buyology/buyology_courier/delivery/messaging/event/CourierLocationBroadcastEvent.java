package com.buyology.buyology_courier.delivery.messaging.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published to {@code buyology.delivery.exchange} with routing key
 * {@code delivery.location.updated} whenever a courier sends a GPS ping
 * while an active delivery is in progress.
 *
 * <p>The ecommerce backend subscribes to this event and forwards the
 * coordinates to the customer in real-time so they can track the courier.</p>
 */
public record CourierLocationBroadcastEvent(
        UUID deliveryId,
        UUID ecommerceOrderId,
        UUID courierId,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal heading,
        BigDecimal speed,
        Instant recordedAt
) {
    public static CourierLocationBroadcastEvent of(
            UUID deliveryId, UUID ecommerceOrderId, UUID courierId,
            BigDecimal lat, BigDecimal lng, BigDecimal heading, BigDecimal speed, Instant recordedAt) {
        return new CourierLocationBroadcastEvent(
                deliveryId, ecommerceOrderId, courierId, lat, lng, heading, speed, recordedAt);
    }
}
