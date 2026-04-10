-- ─────────────────────────────────────────────────────────────────────────────
-- V8 — Add pickup proof columns to delivery_proofs.
--      FAILED is a new DeliveryStatus value — no schema change needed since
--      the status column is VARCHAR(50) and stores enum names as strings.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE delivery_proofs
    ADD COLUMN IF NOT EXISTS pickup_image_url     TEXT,
    ADD COLUMN IF NOT EXISTS pickup_photo_taken_at TIMESTAMPTZ;
