-- ─────────────────────────────────────────────────────────────────────────────
-- V11 — Create chat_messages table for customer ↔ courier in-app messaging.
--
-- Both sides of the conversation (CUSTOMER and COURIER messages) are stored
-- here so the courier app can render a full history without contacting the
-- ecommerce backend.
--
-- delivery_order_id  — cross-service chat-room key (= DeliveryOrder.id here,
--                      = Order.delivery_order_id on the ecommerce side).
-- ecommerce_order_id — ecommerce Order.id, kept for cross-service lookups.
-- sender_type        — CUSTOMER | COURIER
-- message_type       — TEXT | CALL_OFFER | CALL_ANSWER | CALL_ICE_CANDIDATE |
--                      CALL_END | CALL_REJECT
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS chat_messages (
    id                  UUID        NOT NULL,
    delivery_order_id   UUID        NOT NULL,
    ecommerce_order_id  UUID        NOT NULL,
    sender_id           UUID        NOT NULL,
    sender_type         VARCHAR(20) NOT NULL,
    message_type        VARCHAR(30) NOT NULL,
    content             TEXT        NOT NULL,
    sent_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at        TIMESTAMPTZ,
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_chat_messages PRIMARY KEY (id),
    CONSTRAINT chk_sender_type  CHECK (sender_type  IN ('CUSTOMER', 'COURIER')),
    CONSTRAINT chk_message_type CHECK (message_type IN (
        'TEXT', 'CALL_OFFER', 'CALL_ANSWER', 'CALL_ICE_CANDIDATE', 'CALL_END', 'CALL_REJECT'
    ))
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_delivery_order
    ON chat_messages (delivery_order_id, sent_at DESC);
