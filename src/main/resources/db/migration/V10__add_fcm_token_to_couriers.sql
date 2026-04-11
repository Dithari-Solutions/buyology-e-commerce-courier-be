-- ─────────────────────────────────────────────────────────────────────────────
-- V10 — Add fcm_token to couriers so the backend can send push notifications
--       to the courier's mobile device via Firebase Cloud Messaging (FCM).
--       NULL until the courier registers a device token after login.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE couriers
    ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(512);
