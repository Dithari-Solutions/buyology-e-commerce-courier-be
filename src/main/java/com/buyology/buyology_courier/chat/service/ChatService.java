package com.buyology.buyology_courier.chat.service;

import com.buyology.buyology_courier.chat.domain.enums.MessageType;
import com.buyology.buyology_courier.chat.dto.ChatMessageResponse;
import com.buyology.buyology_courier.chat.dto.SendMessageRequest;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.UUID;

public interface ChatService {

    /**
     * Validates that the courier is assigned to the delivery and it is active,
     * persists the message, relays it to the ecommerce backend via RabbitMQ, and
     * echoes it back to the courier's own WebSocket session.
     */
    ChatMessageResponse sendFromCourier(UUID deliveryOrderId,
                                        UUID courierId,
                                        SendMessageRequest request);

    /**
     * Called by the RabbitMQ consumer when a customer message arrives from the
     * ecommerce backend. Persists it and pushes to the courier's WebSocket.
     */
    void receiveFromCustomer(UUID deliveryOrderId,
                             UUID ecommerceOrderId,
                             UUID customerId,
                             MessageType messageType,
                             String content,
                             Instant sentAt,
                             UUID messageId);

    /**
     * Paginated chat history for the courier's history screen.
     * The caller must be the assigned courier for this delivery.
     */
    Page<ChatMessageResponse> getHistory(UUID deliveryOrderId, UUID courierId, int page, int size);
}
