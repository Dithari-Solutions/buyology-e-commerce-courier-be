package com.buyology.buyology_courier.chat.controller;

import com.buyology.buyology_courier.chat.dto.ChatMessageResponse;
import com.buyology.buyology_courier.chat.service.ChatService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoint for fetching the courier's chat history for a specific delivery.
 *
 * <p>Used by the courier app to render the conversation on screen load before
 * the WebSocket is connected for live updates.
 */
@RestController
@RequestMapping("/api/deliveries/{deliveryId}/chat")
public class ChatRestController {

    private final ChatService chatService;

    public ChatRestController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Returns paginated chat history for the delivery.
     * The caller must be the assigned courier.
     *
     * @param deliveryId the DeliveryOrder.id (= chat room key)
     * @param page       zero-based page index (default 0)
     * @param size       page size (default 50, max 100)
     */
    @GetMapping
    public ResponseEntity<Page<ChatMessageResponse>> getHistory(
            @PathVariable UUID deliveryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal Jwt jwt) {

        UUID courierId = UUID.fromString(jwt.getSubject());
        int safeSize = Math.min(size, 100);
        Page<ChatMessageResponse> history = chatService.getHistory(deliveryId, courierId, page, safeSize);
        return ResponseEntity.ok(history);
    }
}
