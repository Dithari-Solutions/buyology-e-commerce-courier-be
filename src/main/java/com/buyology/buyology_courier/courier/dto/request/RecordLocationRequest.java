package com.buyology.buyology_courier.courier.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record RecordLocationRequest(

        @NotNull
        @DecimalMin("-90.0") @DecimalMax("90.0")
        BigDecimal latitude,

        @NotNull
        @DecimalMin("-180.0") @DecimalMax("180.0")
        BigDecimal longitude,

        // Degrees 0–360 from north
        @DecimalMin("0.0") @DecimalMax("360.0")
        BigDecimal heading,

        // Speed must be non-negative — negative speed is physically meaningless
        @DecimalMin(value = "0.0", message = "Speed cannot be negative")
        BigDecimal speed,

        // GPS accuracy radius in metres — must be a positive measurement
        @DecimalMin(value = "0.0", message = "Accuracy metres cannot be negative")
        BigDecimal accuracyMeters,

        // If null, server timestamp is used. Must not be a future timestamp — prevents
        // corrupting the tracking history ordering with fabricated future pings.
        @PastOrPresent
        Instant recordedAt
) {}
