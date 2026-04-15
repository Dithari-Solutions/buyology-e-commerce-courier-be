package com.buyology.buyology_courier.chat.messaging;

import com.buyology.buyology_courier.chat.domain.enums.MessageType;
import com.buyology.buyology_courier.chat.domain.enums.SenderType;
import com.buyology.buyology_courier.chat.messaging.event.ChatMessageEvent;
import com.buyology.buyology_courier.chat.service.ChatService;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.courier.repository.CourierRepository;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.buyology.buyology_courier.delivery.repository.DeliveryOrderRepository;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Consumes chat messages sent by customers from the ecommerce backend via
 * RabbitMQ ({@code chat.from.customer.queue}), then:
 * <ol>
 *   <li>Persists the message in the courier {@code chat_messages} table.</li>
 *   <li>Pushes it to the courier's live WebSocket session.</li>
 *   <li>Falls back to an FCM push notification if the courier is not connected.</li>
 * </ol>
 */
@Component
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);

    private final ChatService chatService;
    private final DeliveryOrderRepository deliveryOrderRepository;
    private final CourierRepository courierRepository;

    /** Null when firebase.enabled=false — FCM calls are skipped gracefully. */
    @Autowired(required = false)
    private FirebaseMessaging firebaseMessaging;

    public ChatMessageConsumer(ChatService chatService,
                               DeliveryOrderRepository deliveryOrderRepository,
                               CourierRepository courierRepository) {
        this.chatService = chatService;
        this.deliveryOrderRepository = deliveryOrderRepository;
        this.courierRepository = courierRepository;
    }

    @RabbitListener(queues = ChatRabbitMQConfig.CHAT_FROM_CUSTOMER_QUEUE)
    public void onCustomerMessage(ChatMessageEvent event) {
        log.info("[Chat] Received customer message deliveryOrderId={} type={}",
                event.deliveryOrderId(), event.messageType());

        chatService.receiveFromCustomer(
                event.deliveryOrderId(),
                event.ecommerceOrderId(),
                event.senderId(),
                event.messageType(),
                event.content(),
                event.sentAt(),
                event.messageId()
        );

        // FCM fallback for TEXT messages only
        if (event.messageType() == MessageType.TEXT) {
            sendFcmFallback(event);
        }
    }

    private void sendFcmFallback(ChatMessageEvent event) {
        if (firebaseMessaging == null) return;

        try {
            DeliveryOrder delivery = deliveryOrderRepository
                    .findById(event.deliveryOrderId()).orElse(null);
            if (delivery == null || delivery.getAssignedCourier() == null) return;

            Courier courier = delivery.getAssignedCourier();
            if (courier.getFcmToken() == null || courier.getFcmToken().isBlank()) return;

            String preview = event.content().length() > 80
                    ? event.content().substring(0, 80) + "…"
                    : event.content();

            Message fcmMsg = Message.builder()
                    .setToken(courier.getFcmToken())
                    .setNotification(Notification.builder()
                            .setTitle("New message from customer")
                            .setBody(preview)
                            .build())
                    .putData("type", "CHAT_MESSAGE")
                    .putData("deliveryOrderId", event.deliveryOrderId().toString())
                    .putData("ecommerceOrderId", event.ecommerceOrderId().toString())
                    .putData("senderType", SenderType.CUSTOMER.name())
                    .build();

            firebaseMessaging.send(fcmMsg);
            log.debug("[Chat] FCM fallback sent to courierId={}", courier.getId());
        } catch (Exception ex) {
            log.warn("[Chat] FCM fallback failed for deliveryOrderId={} — {}",
                    event.deliveryOrderId(), ex.getMessage());
        }
    }
}
