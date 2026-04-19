package com.buyology.buyology_courier.courier.dto.response;

import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CourierMapResponse(
        UUID id,
        String firstName,
        String lastName,
        String phone,
        VehicleType vehicleType,
        CourierStatus status,
        boolean isAvailable,
        BigDecimal rating,
        String profileImageUrl,
        LocationSnapshot latestLocation
) {
    /** Null when the courier has never sent a GPS ping. */
    public record LocationSnapshot(
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal heading,
            BigDecimal speed,
            BigDecimal accuracyMeters,
            Instant recordedAt
    ) {}
}
