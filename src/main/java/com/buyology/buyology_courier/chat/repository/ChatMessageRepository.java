package com.buyology.buyology_courier.chat.repository;

import com.buyology.buyology_courier.chat.domain.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    Page<ChatMessage> findByDeliveryOrderIdOrderBySentAtAsc(UUID deliveryOrderId, Pageable pageable);
}
