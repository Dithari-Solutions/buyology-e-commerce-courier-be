package com.buyology.buyology_courier.delivery.messaging.event;

import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Published to {@code buyology.delivery.exchange} whenever a delivery order
 * changes status. The ecommerce backend (and any other subscriber) consumes
 * this to keep the order lifecycle in sync.
 */
public record DeliveryStatusChangedEvent(

        UUID deliveryId,
        UUID ecommerceOrderId,
        DeliveryStatus status,

        /** Null until a courier is assigned. */
        UUID courierId,

        /** Null for status transitions that have no associated proof photo. */
        String proofImageUrl,

        String changedBy,
        Instant changedAt
) {}
