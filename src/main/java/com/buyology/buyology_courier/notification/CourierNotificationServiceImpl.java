package com.buyology.buyology_courier.notification;

import com.buyology.buyology_courier.assignment.domain.CourierAssignment;
import com.buyology.buyology_courier.config.TwilioSendGridProperties;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Delivers new-assignment notifications via:
 * <ul>
 *   <li>WebSocket/STOMP push — shows "New order available" instantly in the mobile app</li>
 *   <li>Twilio SendGrid email — backup for couriers not actively connected</li>
 * </ul>
 *
 * <p>Both channels run on the {@code eventPublisherExecutor} thread pool so they
 * never block the assignment transaction or the RabbitMQ consumer thread.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourierNotificationServiceImpl implements CourierNotificationService {

    /** STOMP destination suffix — Spring prepends {@code /user/{courierId}}. */
    private static final String ASSIGNMENT_QUEUE = "/queue/assignments";

    private final SimpMessagingTemplate     messagingTemplate;
    private final TwilioSendGridProperties  sendGridProps;

    /** Null when {@code firebase.enabled=false} — FCM pushes are skipped gracefully. */
    @Autowired(required = false)
    private FirebaseMessaging firebaseMessaging;

    @Value("${notification.email.enabled:false}")
    private boolean emailEnabled;

    @Override
    @Async("eventPublisherExecutor")
    public void notifyNewAssignment(Courier courier, CourierAssignment assignment, DeliveryOrder order) {
        pushWebSocket(courier, assignment, order);
        sendFcmPush(courier, assignment, order);
        if (emailEnabled) {
            sendEmail(courier, assignment, order);
        }
    }

    @Override
    @Async("eventPublisherExecutor")
    public void notifyCustomerDelivered(DeliveryOrder order) {
        if (!emailEnabled) return;
        if (order.getCustomerEmail() == null || order.getCustomerEmail().isBlank()) {
            log.debug("[Notification] No customer email on deliveryId={} — skipping delivered email",
                    order.getId());
            return;
        }
        sendCustomerEmail(
                order.getCustomerEmail(),
                order.getCustomerName(),
                "Your order has been delivered — Buyology",
                buildDeliveredEmailBody(order)
        );
    }

    @Override
    @Async("eventPublisherExecutor")
    public void notifyCustomerFailed(DeliveryOrder order, String reason) {
        if (!emailEnabled) return;
        if (order.getCustomerEmail() == null || order.getCustomerEmail().isBlank()) {
            log.debug("[Notification] No customer email on deliveryId={} — skipping failed email",
                    order.getId());
            return;
        }
        sendCustomerEmail(
                order.getCustomerEmail(),
                order.getCustomerName(),
                "We couldn't deliver your order — Buyology",
                buildFailedEmailBody(order, reason)
        );
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private void pushWebSocket(Courier courier, CourierAssignment assignment, DeliveryOrder order) {
        String courierId = courier.getId().toString();
        AssignmentNotificationPayload payload = AssignmentNotificationPayload.of(assignment, order);
        try {
            messagingTemplate.convertAndSendToUser(courierId, ASSIGNMENT_QUEUE, payload);
            log.info("[Notification] WS push sent courierId={} assignmentId={}",
                    courierId, assignment.getId());
        } catch (Exception ex) {
            // Non-fatal: courier may not be connected — email is the fallback
            log.warn("[Notification] WS push failed courierId={} assignmentId={} — {}",
                    courierId, assignment.getId(), ex.getMessage());
        }
    }

    // ── FCM push ──────────────────────────────────────────────────────────────

    private void sendFcmPush(Courier courier, CourierAssignment assignment, DeliveryOrder order) {
        if (firebaseMessaging == null) {
            log.debug("[Notification] FCM disabled (firebase.enabled=false) — skipping push for courierId={}",
                    courier.getId());
            return;
        }
        if (courier.getFcmToken() == null || courier.getFcmToken().isBlank()) {
            log.debug("[Notification] No FCM token for courierId={} — skipping push", courier.getId());
            return;
        }

        try {
            String body = order.getPickupAddress() + " → " + order.getDropoffAddress();

            Message message = Message.builder()
                    .setToken(courier.getFcmToken())
                    .setNotification(Notification.builder()
                            .setTitle("New delivery order")
                            .setBody(body)
                            .build())
                    // Data fields — app reads these to route to the accept/reject screen
                    .putData("assignmentId",  assignment.getId().toString())
                    .putData("deliveryId",    order.getId().toString())
                    .putData("pickupAddress", order.getPickupAddress())
                    .putData("dropoffAddress", order.getDropoffAddress())
                    .putData("deliveryFee",   order.getDeliveryFee() != null ? order.getDeliveryFee().toPlainString() : "")
                    .putData("priority",      order.getPriority() != null ? order.getPriority().name() : "STANDARD")
                    .build();

            String messageId = firebaseMessaging.send(message);
            log.info("[Notification] FCM push sent courierId={} assignmentId={} messageId={}",
                    courier.getId(), assignment.getId(), messageId);

        } catch (FirebaseMessagingException ex) {
            // Non-fatal — WebSocket is the primary real-time channel; FCM is a fallback
            log.warn("[Notification] FCM push failed courierId={} assignmentId={} — {} {}",
                    courier.getId(), assignment.getId(), ex.getErrorCode(), ex.getMessage());
        }
    }

    // ── SendGrid email ────────────────────────────────────────────────────────

    private void sendEmail(Courier courier, CourierAssignment assignment, DeliveryOrder order) {
        if (courier.getEmail() == null || courier.getEmail().isBlank()) {
            log.debug("[Notification] No email on courierId={} — skipping email", courier.getId());
            return;
        }

        try {
            Email from    = new Email(sendGridProps.getFromEmail(), sendGridProps.getFromName());
            Email to      = new Email(courier.getEmail());
            Content content = new Content("text/html", buildEmailBody(courier, assignment, order));
            Mail mail     = new Mail(from, "New delivery order available — Buyology Courier", to, content);

            SendGrid sg = new SendGrid(sendGridProps.getApiKey());
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sg.api(request);

            if (response.getStatusCode() >= 400) {
                log.error("[Notification] SendGrid error {} sending to courierId={} email={}: {}",
                        response.getStatusCode(), courier.getId(), courier.getEmail(), response.getBody());
            } else {
                log.info("[Notification] Email sent via SendGrid courierId={} to={} assignmentId={}",
                        courier.getId(), courier.getEmail(), assignment.getId());
            }
        } catch (IOException ex) {
            log.error("[Notification] Email failed courierId={} to={} — {}",
                    courier.getId(), courier.getEmail(), ex.getMessage());
        }
    }

    // ── Customer emails ───────────────────────────────────────────────────────

    private void sendCustomerEmail(String toAddress, String customerName,
                                   String subject, String htmlBody) {
        try {
            Email from    = new Email(sendGridProps.getFromEmail(), sendGridProps.getFromName());
            Email to      = new Email(toAddress);
            Content content = new Content("text/html", htmlBody);
            Mail mail     = new Mail(from, subject, to, content);

            SendGrid sg = new SendGrid(sendGridProps.getApiKey());
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sg.api(request);

            if (response.getStatusCode() >= 400) {
                log.error("[Notification] SendGrid error {} sending customer email to={}: {}",
                        response.getStatusCode(), toAddress, response.getBody());
            } else {
                log.info("[Notification] Customer email sent to={} subject='{}'", toAddress, subject);
            }
        } catch (IOException ex) {
            log.error("[Notification] Customer email failed to={} — {}", toAddress, ex.getMessage());
        }
    }

    private String buildDeliveredEmailBody(DeliveryOrder order) {
        return """
                <html><body style="font-family:Arial,sans-serif;color:#333">
                <h2 style="color:#2e7d32">Your order has been delivered!</h2>
                <p>Hi %s,</p>
                <p>Great news — your package has been successfully delivered to the address below.</p>
                <table style="border-collapse:collapse;width:100%%">
                  <tr><td style="padding:6px;font-weight:bold">Delivery address</td>
                      <td style="padding:6px">%s</td></tr>
                  <tr style="background:#f5f5f5">
                      <td style="padding:6px;font-weight:bold">Order reference</td>
                      <td style="padding:6px;font-size:12px;color:#888">%s</td></tr>
                </table>
                <p>Thank you for shopping with Buyology!</p>
                <p style="margin-top:24px;font-size:12px;color:#aaa">
                  This is an automated message from Buyology. Do not reply.
                </p>
                </body></html>
                """.formatted(
                order.getCustomerName(),
                order.getDropoffAddress(),
                order.getEcommerceOrderId()
        );
    }

    private String buildFailedEmailBody(DeliveryOrder order, String reason) {
        return """
                <html><body style="font-family:Arial,sans-serif;color:#333">
                <h2 style="color:#c62828">We couldn't deliver your order</h2>
                <p>Hi %s,</p>
                <p>Unfortunately our courier was unable to complete your delivery. Here are the details:</p>
                <table style="border-collapse:collapse;width:100%%">
                  <tr><td style="padding:6px;font-weight:bold">Delivery address</td>
                      <td style="padding:6px">%s</td></tr>
                  <tr style="background:#f9f0f0">
                      <td style="padding:6px;font-weight:bold;color:#c62828">Reason</td>
                      <td style="padding:6px;color:#c62828">%s</td></tr>
                  <tr><td style="padding:6px;font-weight:bold">Order reference</td>
                      <td style="padding:6px;font-size:12px;color:#888">%s</td></tr>
                </table>
                <p>Please contact our support team if you have any questions.</p>
                <p style="margin-top:24px;font-size:12px;color:#aaa">
                  This is an automated message from Buyology. Do not reply.
                </p>
                </body></html>
                """.formatted(
                order.getCustomerName(),
                order.getDropoffAddress(),
                reason,
                order.getEcommerceOrderId()
        );
    }

    // ── Courier assignment email ───────────────────────────────────────────────

    private String buildEmailBody(Courier courier, CourierAssignment assignment, DeliveryOrder order) {
        return """
                <html><body style="font-family:Arial,sans-serif;color:#333">
                <h2 style="color:#e65c00">New delivery order available</h2>
                <p>Hi %s,</p>
                <p>A new delivery has been assigned to you. Open the app to accept or reject it.</p>
                <table style="border-collapse:collapse;width:100%%">
                  <tr><td style="padding:6px;font-weight:bold">Pickup</td>
                      <td style="padding:6px">%s</td></tr>
                  <tr style="background:#f5f5f5">
                      <td style="padding:6px;font-weight:bold">Drop-off</td>
                      <td style="padding:6px">%s</td></tr>
                  <tr><td style="padding:6px;font-weight:bold">Priority</td>
                      <td style="padding:6px">%s</td></tr>
                  <tr style="background:#f5f5f5">
                      <td style="padding:6px;font-weight:bold">Delivery fee</td>
                      <td style="padding:6px">%s</td></tr>
                  <tr><td style="padding:6px;font-weight:bold">Assignment ID</td>
                      <td style="padding:6px;font-size:12px;color:#888">%s</td></tr>
                </table>
                <p style="margin-top:24px;font-size:12px;color:#aaa">
                  This is an automated message from Buyology Courier. Do not reply.
                </p>
                </body></html>
                """.formatted(
                courier.getFirstName(),
                order.getPickupAddress(),
                order.getDropoffAddress(),
                order.getPriority()    != null ? order.getPriority().name()    : "STANDARD",
                order.getDeliveryFee() != null ? order.getDeliveryFee().toPlainString() : "—",
                assignment.getId()
        );
    }
}
