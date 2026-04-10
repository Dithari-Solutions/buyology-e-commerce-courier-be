package com.buyology.buyology_courier.notification;

import com.buyology.buyology_courier.assignment.domain.CourierAssignment;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;

/**
 * Notifies couriers and customers about delivery events via:
 * <ul>
 *   <li>WebSocket/STOMP push — shown immediately in the courier mobile app</li>
 *   <li>Email — SendGrid for both couriers (assignment fallback) and customers (delivery outcomes)</li>
 * </ul>
 */
public interface CourierNotificationService {

    /**
     * Called immediately after an assignment is persisted.
     * Sends a WebSocket push and (if the courier has an email) a fallback email.
     */
    void notifyNewAssignment(Courier courier, CourierAssignment assignment, DeliveryOrder order);

    /**
     * Sends the customer a delivery-confirmation email once the package is marked DELIVERED.
     * No-op if the delivery has no customer email address or email is disabled.
     */
    void notifyCustomerDelivered(DeliveryOrder order);

    /**
     * Sends the customer a failure-notification email including the reason provided by the courier.
     * No-op if the delivery has no customer email address or email is disabled.
     */
    void notifyCustomerFailed(DeliveryOrder order, String reason);
}
