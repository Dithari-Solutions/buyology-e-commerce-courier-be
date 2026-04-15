package com.buyology.buyology_courier.chat.dto;

import com.buyology.buyology_courier.chat.domain.enums.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload sent by the courier over the STOMP WebSocket to
 * {@code /app/chat/{deliveryOrderId}/send}.
 */
public record SendMessageRequest(

        @NotNull
        MessageType messageType,

        @NotBlank
        @Size(max = 4000)
        String content
) {}
