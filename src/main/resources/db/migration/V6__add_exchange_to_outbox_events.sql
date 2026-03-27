-- ─────────────────────────────────────────────────────────────────────────────
-- V6 — Add exchange column to outbox_events
--
-- Allows the OutboxPublisherJob to route events to different exchanges
-- (e.g. buyology.courier.exchange for courier events,
--       buyology.delivery.exchange for delivery status updates).
-- Existing rows default to the original courier exchange so no data is lost.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE outbox_events
    ADD COLUMN exchange VARCHAR(150) NOT NULL DEFAULT 'buyology.courier.exchange';
