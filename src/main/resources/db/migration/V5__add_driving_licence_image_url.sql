-- ─────────────────────────────────────────────────────────────────────────────
-- V5 — Add driving_licence_image_url to couriers
-- ─────────────────────────────────────────────────────────────────────────────
-- Stores the front-face driving licence image URL directly on the courier
-- profile so it can be returned in CourierResponse without a join to
-- courier_vehicle_details. NULL for BICYCLE / FOOT couriers.
ALTER TABLE couriers
    ADD COLUMN driving_licence_image_url TEXT;
