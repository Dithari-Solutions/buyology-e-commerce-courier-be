package com.buyology.buyology_courier.chat.dto;

import com.buyology.buyology_courier.chat.domain.ChatMessage;
import com.buyology.buyology_courier.chat.domain.enums.MessageType;
import com.buyology.buyology_courier.chat.domain.enums.SenderType;

import java.time.Instant;
import java.util.UUID;

/**
 * Returned to couriers both over WebSocket (real-time push) and the REST history endpoint.
 */
public record ChatMessageResponse(
        UUID messageId,
        UUID deliveryOrderId,
        UUID ecommerceOrderId,
        UUID senderId,
        SenderType senderType,
        MessageType messageType,
        String content,
        Instant sentAt,
        Instant deliveredAt,
        Instant readAt
) {
    public static ChatMessageResponse from(ChatMessage m) {
        return new ChatMessageResponse(
                m.getId(),
                m.getDeliveryOrderId(),
                m.getEcommerceOrderId(),
                m.getSenderId(),
                m.getSenderType(),
                m.getMessageType(),
                m.getContent(),
                m.getSentAt(),
                m.getDeliveredAt(),
                m.getReadAt()
        );
    }
}
