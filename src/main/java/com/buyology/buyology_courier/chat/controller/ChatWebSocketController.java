package com.buyology.buyology_courier.chat.controller;

import com.buyology.buyology_courier.chat.dto.SendMessageRequest;
import com.buyology.buyology_courier.chat.service.ChatService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * Handles inbound STOMP messages from the courier.
 *
 * <h3>Client sends to:</h3>
 * {@code /app/chat/{deliveryOrderId}/send}
 *
 * <h3>Client receives on:</h3>
 * {@code /user/queue/chat/{deliveryOrderId}}
 *
 * <p>Authentication: the STOMP CONNECT frame must carry a valid courier JWT in the
 * {@code Authorization: Bearer <token>} native header (validated by
 * {@link com.buyology.buyology_courier.notification.WebSocketAuthChannelInterceptor}).
 */
@Controller
public class ChatWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);

    private final ChatService chatService;

    public ChatWebSocketController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Receives a message from the courier and relays it to the customer.
     *
     * @param deliveryOrderId the chat-room key (DeliveryOrder.id)
     * @param request         the message payload
     * @param principal       authenticated courier (set by WebSocketAuthChannelInterceptor)
     */
    @MessageMapping("/chat/{deliveryOrderId}/send")
    public void send(@DestinationVariable UUID deliveryOrderId,
                     @Valid @Payload SendMessageRequest request,
                     Principal principal) {

        UUID courierId = UUID.fromString(principal.getName());
        log.debug("[Chat-WS] Courier {} → deliveryOrderId={} type={}",
                courierId, deliveryOrderId, request.messageType());

        chatService.sendFromCourier(deliveryOrderId, courierId, request);
    }
}
