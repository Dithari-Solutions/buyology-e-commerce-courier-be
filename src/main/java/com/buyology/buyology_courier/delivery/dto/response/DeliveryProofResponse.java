package com.buyology.buyology_courier.delivery.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Returned after a courier submits a pickup or delivery proof photo.
 * Both pickup and delivery proof data are included so the mobile app
 * can display the full evidence chain for any delivery.
 */
public record DeliveryProofResponse(
        UUID id,
        UUID deliveryId,

        /** Photo taken at the pickup location — confirms courier received the package. */
        String pickupImageUrl,
        Instant pickupPhotoTakenAt,

        /** Photo taken at the dropoff location — confirms successful delivery. */
        String imageUrl,
        String signatureUrl,
        String deliveredTo,
        Instant photoTakenAt,

        Instant createdAt
) {}
