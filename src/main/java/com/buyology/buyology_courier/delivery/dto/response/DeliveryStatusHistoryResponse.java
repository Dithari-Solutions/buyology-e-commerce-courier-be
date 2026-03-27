package com.buyology.buyology_courier.delivery.dto.response;

import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DeliveryStatusHistoryResponse(

        UUID id,
        DeliveryStatus status,
        BigDecimal latitude,
        BigDecimal longitude,
        String changedBy,
        String notes,
        Instant createdAt
) {}
