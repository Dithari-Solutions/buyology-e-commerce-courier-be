package com.buyology.buyology_courier.assignment.service.event;

import com.buyology.buyology_courier.assignment.domain.CourierAssignment;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;

import java.util.UUID;

/**
 * Triggered after a new courier assignment is persisted to the DB.
 * Used to send push notifications and emails AFTER the transaction commits.
 */
public record CourierAssignedApplicationEvent(
        Courier courier,
        CourierAssignment assignment,
        DeliveryOrder order
) {
    public static CourierAssignedApplicationEvent of(Courier courier, CourierAssignment assignment, DeliveryOrder order) {
        return new CourierAssignedApplicationEvent(courier, assignment, order);
    }
}
