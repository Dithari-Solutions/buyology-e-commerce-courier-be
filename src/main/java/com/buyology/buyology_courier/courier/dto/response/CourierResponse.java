package com.buyology.buyology_courier.courier.dto.response;

import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CourierResponse(
        UUID id,
        String firstName,
        String lastName,
        String phone,
        String email,
        VehicleType vehicleType,
        CourierStatus status,
        boolean isAvailable,
        BigDecimal rating,
        String profileImageUrl,
        String drivingLicenceImageUrl,
        Instant createdAt,
        Instant updatedAt
) {}
