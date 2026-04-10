-- V8 — Add customer_email to delivery_orders
-- Required by DeliveryOrder entity (added for SendGrid customer notifications).
-- Nullable — existing rows and ecommerce integrations that omit the field are unaffected.

ALTER TABLE delivery_orders
    ADD COLUMN IF NOT EXISTS customer_email VARCHAR(150);
