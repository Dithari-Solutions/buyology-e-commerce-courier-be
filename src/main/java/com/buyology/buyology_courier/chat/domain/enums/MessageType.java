package com.buyology.buyology_courier.chat.domain.enums;

/**
 * Type of chat message.
 * Call-signal types are courier-app only; web sessions on the ecommerce side
 * have these rejected at the ecommerce backend before they reach this service.
 */
public enum MessageType {
    TEXT,
    CALL_OFFER,
    CALL_ANSWER,
    CALL_ICE_CANDIDATE,
    CALL_END,
    CALL_REJECT
}
