package com.buyology.buyology_courier.chat.domain;

import com.buyology.buyology_courier.chat.domain.enums.MessageType;
import com.buyology.buyology_courier.chat.domain.enums.SenderType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted chat message between the courier and the customer for an active delivery.
 *
 * <p>Both sides of the conversation (CUSTOMER and COURIER messages) are stored so
 * the courier can view the full history via {@code GET /api/deliveries/{deliveryId}/chat}.
 *
 * <p>{@code deliveryOrderId} is the cross-service chat-room key — matches
 * {@code DeliveryOrder.id} and {@code Order.deliveryOrderId} on the ecommerce side.
 */
@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_messages_delivery_order", columnList = "delivery_order_id, sent_at DESC")
})
public class ChatMessage {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "delivery_order_id", nullable = false)
    private UUID deliveryOrderId;

    @Column(name = "ecommerce_order_id", nullable = false)
    private UUID ecommerceOrderId;

    /** UUID of the courier or the customer. */
    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 20)
    private SenderType senderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 30)
    private MessageType messageType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        if (this.sentAt == null) this.sentAt = now;
        if (this.id == null) this.id = UUID.randomUUID();
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getDeliveryOrderId() { return deliveryOrderId; }
    public void setDeliveryOrderId(UUID deliveryOrderId) { this.deliveryOrderId = deliveryOrderId; }

    public UUID getEcommerceOrderId() { return ecommerceOrderId; }
    public void setEcommerceOrderId(UUID ecommerceOrderId) { this.ecommerceOrderId = ecommerceOrderId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public SenderType getSenderType() { return senderType; }
    public void setSenderType(SenderType senderType) { this.senderType = senderType; }

    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }

    public Instant getCreatedAt() { return createdAt; }
}
