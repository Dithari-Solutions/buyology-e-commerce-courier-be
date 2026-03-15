package com.buyology.buyology_courier.courier.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CourierLocationResponse(
        UUID id,
        UUID courierId,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal heading,
        BigDecimal speed,
        BigDecimal accuracyMeters,
        Instant recordedAt
) {}
