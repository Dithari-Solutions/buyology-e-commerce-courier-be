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

    /**
     * Notifies the assigned courier (FCM push + email) that the delivery they completed
     * is now marked DELIVERED. Fired after submitDeliveryProof succeeds.
     * No-op if the delivery has no assigned courier or the courier has no FCM token / email.
     */
    void notifyCourierDelivered(DeliveryOrder order);

    /**
     * Notifies the assigned courier (FCM push + email) that the order has been cancelled
     * by the customer or operations. Fired by the cancel flow.
     * No-op if no courier is assigned or the courier has no FCM token / email.
     */
    void notifyCourierCancelled(DeliveryOrder order, String reason);
}
