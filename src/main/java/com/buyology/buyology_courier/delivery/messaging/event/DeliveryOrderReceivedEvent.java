package com.buyology.buyology_courier.delivery.messaging.event;

import com.buyology.buyology_courier.delivery.domain.enums.DeliveryPriority;
import com.buyology.buyology_courier.delivery.domain.enums.PackageSize;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published by the ecommerce backend when an order requires delivery.
 * Consumed from {@code delivery.order.received.queue}.
 *
 * <p>Processing is idempotent: if a {@code DeliveryOrder} already exists for
 * {@code ecommerceOrderId}, the message is acked and silently skipped.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryOrderReceivedEvent(

        UUID ecommerceOrderId,
        UUID ecommerceStoreId,

        String customerName,
        String customerPhone,

        String pickupAddress,
        BigDecimal pickupLat,
        BigDecimal pickupLng,

        String dropoffAddress,
        BigDecimal dropoffLat,
        BigDecimal dropoffLng,

        PackageSize packageSize,
        BigDecimal packageWeight,
        BigDecimal deliveryFee,

        DeliveryPriority priority,

        Instant occurredAt
) {}
