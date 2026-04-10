package com.buyology.buyology_courier.notification;

import com.buyology.buyology_courier.assignment.domain.CourierAssignment;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pushed over WebSocket to the courier mobile app when a new delivery is assigned.
 *
 * <p>The app receives this on {@code /user/queue/assignments} and displays
 * the "New order available" screen with pickup/dropoff details.
 * The courier then calls {@code POST /api/v1/assignments/{assignmentId}/respond}
 * to accept or reject.
 */
public record AssignmentNotificationPayload(
        UUID assignmentId,
        UUID deliveryId,
        int  attemptNumber,

        String pickupAddress,
        BigDecimal pickupLat,
        BigDecimal pickupLng,

        String dropoffAddress,
        BigDecimal dropoffLat,
        BigDecimal dropoffLng,

        String packageSize,
        BigDecimal packageWeight,
        BigDecimal deliveryFee,
        String priority,

        Instant assignedAt
) {
    public static AssignmentNotificationPayload of(CourierAssignment assignment, DeliveryOrder order) {
        return new AssignmentNotificationPayload(
                assignment.getId(),
                order.getId(),
                assignment.getAttemptNumber(),
                order.getPickupAddress(),
                order.getPickupLat(),
                order.getPickupLng(),
                order.getDropoffAddress(),
                order.getDropoffLat(),
                order.getDropoffLng(),
                order.getPackageSize() != null ? order.getPackageSize().name() : null,
                order.getPackageWeight(),
                order.getDeliveryFee(),
                order.getPriority() != null ? order.getPriority().name() : null,
                assignment.getAssignedAt()
        );
    }
}
