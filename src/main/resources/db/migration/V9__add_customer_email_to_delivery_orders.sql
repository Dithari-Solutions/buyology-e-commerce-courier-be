-- ─────────────────────────────────────────────────────────────────────────────
-- V9 — Add customer_email to delivery_orders so the courier service can send
--      delivery confirmation and failure notification emails directly to customers.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE delivery_orders
    ADD COLUMN IF NOT EXISTS customer_email VARCHAR(150);
