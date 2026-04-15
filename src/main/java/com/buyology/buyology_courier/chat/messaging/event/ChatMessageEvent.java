package com.buyology.buyology_courier.chat.messaging.event;

import com.buyology.buyology_courier.chat.domain.enums.MessageType;
import com.buyology.buyology_courier.chat.domain.enums.SenderType;

import java.time.Instant;
import java.util.UUID;

/**
 * RabbitMQ event record published/consumed for cross-service chat relay.
 *
 * <ul>
 *   <li>Courier → Ecommerce  routing key: {@code chat.message.courier}</li>
 *   <li>Ecommerce → Courier  routing key: {@code chat.message.customer}</li>
 * </ul>
 */
public record ChatMessageEvent(
        UUID messageId,
        UUID deliveryOrderId,
        UUID ecommerceOrderId,
        UUID senderId,
        SenderType senderType,
        MessageType messageType,
        String content,
        Instant sentAt
) {}
