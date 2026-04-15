package com.buyology.buyology_courier.chat.service.impl;

import com.buyology.buyology_courier.chat.domain.ChatMessage;
import com.buyology.buyology_courier.chat.domain.enums.MessageType;
import com.buyology.buyology_courier.chat.domain.enums.SenderType;
import com.buyology.buyology_courier.chat.dto.ChatMessageResponse;
import com.buyology.buyology_courier.chat.dto.SendMessageRequest;
import com.buyology.buyology_courier.chat.messaging.event.ChatMessageEvent;
import com.buyology.buyology_courier.chat.repository.ChatMessageRepository;
import com.buyology.buyology_courier.chat.service.ChatService;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.repository.DeliveryOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /** Delivery statuses where the chat channel is open. */
    private static final Set<DeliveryStatus> ACTIVE_STATUSES = Set.of(
            DeliveryStatus.COURIER_ASSIGNED,
            DeliveryStatus.COURIER_ACCEPTED,
            DeliveryStatus.ARRIVED_AT_PICKUP,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.ON_THE_WAY,
            DeliveryStatus.ARRIVED_AT_DESTINATION
    );

    private static final Set<DeliveryStatus> TERMINAL_STATUSES = Set.of(
            DeliveryStatus.DELIVERED,
            DeliveryStatus.FAILED,
            DeliveryStatus.CANCELLED
    );

    private static final String DELIVERY_EXCHANGE      = "buyology.delivery.exchange";
    private static final String CHAT_FROM_COURIER_KEY  = "chat.message.courier";

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RabbitTemplate rabbitTemplate;

    public ChatServiceImpl(DeliveryOrderRepository deliveryOrderRepository,
                           ChatMessageRepository chatMessageRepository,
                           SimpMessagingTemplate messagingTemplate,
                           RabbitTemplate rabbitTemplate) {
        this.deliveryOrderRepository = deliveryOrderRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.messagingTemplate = messagingTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    @Transactional
    public ChatMessageResponse sendFromCourier(UUID deliveryOrderId,
                                               UUID courierId,
                                               SendMessageRequest request) {
        DeliveryOrder delivery = deliveryOrderRepository.findById(deliveryOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found: " + deliveryOrderId));

        // ── Authorization ──────────────────────────────────────────────────────
        if (delivery.getAssignedCourier() == null
                || !delivery.getAssignedCourier().getId().equals(courierId)) {
            throw new AccessDeniedException("You are not assigned to this delivery");
        }

        // ── Active-delivery guard ──────────────────────────────────────────────
        if (TERMINAL_STATUSES.contains(delivery.getStatus())) {
            throw new IllegalStateException(
                    "Chat is closed for delivery " + deliveryOrderId
                            + " (status: " + delivery.getStatus() + ")");
        }
        if (!ACTIVE_STATUSES.contains(delivery.getStatus())) {
            throw new IllegalStateException(
                    "Chat not yet available — delivery status is " + delivery.getStatus());
        }

        // ── Persist ───────────────────────────────────────────────────────────
        ChatMessage msg = new ChatMessage();
        msg.setId(UUID.randomUUID());
        msg.setDeliveryOrderId(deliveryOrderId);
        msg.setEcommerceOrderId(delivery.getEcommerceOrderId());
        msg.setSenderId(courierId);
        msg.setSenderType(SenderType.COURIER);
        msg.setMessageType(request.messageType());
        msg.setContent(request.content());
        msg.setSentAt(Instant.now());
        chatMessageRepository.save(msg);

        // ── Relay to ecommerce backend via RabbitMQ ───────────────────────────
        ChatMessageEvent event = new ChatMessageEvent(
                msg.getId(),
                deliveryOrderId,
                delivery.getEcommerceOrderId(),
                courierId,
                SenderType.COURIER,
                request.messageType(),
                request.content(),
                msg.getSentAt()
        );
        rabbitTemplate.convertAndSend(DELIVERY_EXCHANGE, CHAT_FROM_COURIER_KEY, event);
        log.debug("[Chat] Relayed courier message to ecommerce backend deliveryOrderId={}", deliveryOrderId);

        // ── Echo to courier's own WS session ───────────────────────────────────
        ChatMessageResponse response = ChatMessageResponse.from(msg);
        messagingTemplate.convertAndSendToUser(
                courierId.toString(),
                "/queue/chat/" + deliveryOrderId,
                response
        );
        return response;
    }

    @Override
    @Transactional
    public void receiveFromCustomer(UUID deliveryOrderId,
                                    UUID ecommerceOrderId,
                                    UUID customerId,
                                    MessageType messageType,
                                    String content,
                                    Instant sentAt,
                                    UUID messageId) {
        DeliveryOrder delivery = deliveryOrderRepository.findById(deliveryOrderId).orElse(null);
        if (delivery == null) {
            log.warn("[Chat] Received customer message for unknown deliveryOrderId={}", deliveryOrderId);
            return;
        }

        // ── Persist (idempotent) ───────────────────────────────────────────────
        if (!chatMessageRepository.existsById(messageId)) {
            ChatMessage msg = new ChatMessage();
            msg.setId(messageId);
            msg.setDeliveryOrderId(deliveryOrderId);
            msg.setEcommerceOrderId(ecommerceOrderId);
            msg.setSenderId(customerId);
            msg.setSenderType(SenderType.CUSTOMER);
            msg.setMessageType(messageType);
            msg.setContent(content);
            msg.setSentAt(sentAt);
            msg.setDeliveredAt(Instant.now());
            chatMessageRepository.save(msg);
        }

        // ── Push to courier's WS ──────────────────────────────────────────────
        if (delivery.getAssignedCourier() != null) {
            ChatMessage persisted = chatMessageRepository.findById(messageId).orElseThrow();
            ChatMessageResponse response = ChatMessageResponse.from(persisted);
            String courierId = delivery.getAssignedCourier().getId().toString();
            messagingTemplate.convertAndSendToUser(
                    courierId,
                    "/queue/chat/" + deliveryOrderId,
                    response
            );
            log.debug("[Chat] Pushed customer message to courier={} deliveryOrderId={}",
                    courierId, deliveryOrderId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ChatMessageResponse> getHistory(UUID deliveryOrderId, UUID courierId, int page, int size) {
        DeliveryOrder delivery = deliveryOrderRepository.findById(deliveryOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found: " + deliveryOrderId));

        if (delivery.getAssignedCourier() == null
                || !delivery.getAssignedCourier().getId().equals(courierId)) {
            throw new AccessDeniedException("You are not assigned to this delivery");
        }

        return chatMessageRepository
                .findByDeliveryOrderIdOrderBySentAtAsc(deliveryOrderId, PageRequest.of(page, size))
                .map(ChatMessageResponse::from);
    }
}
