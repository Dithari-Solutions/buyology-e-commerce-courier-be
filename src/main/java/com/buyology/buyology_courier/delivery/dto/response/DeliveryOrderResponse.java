package com.buyology.buyology_courier.delivery.dto.response;

import com.buyology.buyology_courier.delivery.domain.enums.DeliveryPriority;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.domain.enums.PackageSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DeliveryOrderResponse(

        UUID id,
        UUID ecommerceOrderId,
        UUID ecommerceStoreId,

        String customerName,
        String customerPhone,
        String customerEmail,

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
        DeliveryStatus status,

        UUID assignedCourierId,

        Instant estimatedDeliveryTime,
        Instant actualDeliveryTime,
        String cancelledReason,

        Instant createdAt,
        Instant updatedAt
) {}
