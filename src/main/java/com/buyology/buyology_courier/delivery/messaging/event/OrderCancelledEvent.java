package com.buyology.buyology_courier.delivery.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed from {@code buyology.ecommerce.exchange} routing key
 * {@code order.delivery.cancelled}. Published by the ecommerce backend when an
 * EXPRESS order is cancelled so the courier backend can stop the in-flight delivery.
 */
public record OrderCancelledEvent(
        UUID ecommerceOrderId,
        String reason,
        Instant cancelledAt
) {}
