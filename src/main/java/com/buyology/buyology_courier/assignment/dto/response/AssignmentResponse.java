package com.buyology.buyology_courier.assignment.dto.response;

import com.buyology.buyology_courier.assignment.domain.enums.AssignmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a courier assignment.
 * Includes an embedded delivery address summary so the courier app
 * can display pickup/dropoff details without a separate delivery API call.
 */
public record AssignmentResponse(
        UUID id,
        UUID deliveryId,
        UUID courierId,
        AssignmentStatus status,
        int attemptNumber,
        Instant assignedAt,
        Instant acceptedAt,
        Instant rejectedAt,
        String rejectionReason,
        Instant createdAt,

        // Embedded delivery summary for the courier app
        String pickupAddress,
        BigDecimal pickupLat,
        BigDecimal pickupLng,
        String dropoffAddress,
        BigDecimal dropoffLat,
        BigDecimal dropoffLng
) {}
