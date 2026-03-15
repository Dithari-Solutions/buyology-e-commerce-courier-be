-- ─────────────────────────────────────────────────────────────────────────────
-- V1 — Initial schema for buyology-courier
-- ─────────────────────────────────────────────────────────────────────────────

-- PostGIS required for delivery_zones.polygon (GEOGRAPHY type)
CREATE EXTENSION IF NOT EXISTS postgis;

-- ─── COURIERS ─────────────────────────────────────────────────────────────────
CREATE TABLE couriers
(
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    -- Optimistic locking — prevents silent overwrites on concurrent updates
    version           BIGINT       NOT NULL DEFAULT 0,
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL,
    phone             VARCHAR(30)  NOT NULL,
    email             VARCHAR(150),
    vehicle_type      VARCHAR(50)  NOT NULL,
    status            VARCHAR(30)  NOT NULL,
    is_available      BOOLEAN      NOT NULL DEFAULT FALSE,
    rating            NUMERIC(3, 1),
    profile_image_url TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Soft delete — never hard-delete; all historical delivery/earnings data stays intact
    deleted_at        TIMESTAMPTZ,

    CONSTRAINT uk_couriers_phone UNIQUE (phone)
);

CREATE INDEX idx_couriers_status           ON couriers (status);
CREATE INDEX idx_couriers_is_available     ON couriers (is_available);
CREATE INDEX idx_couriers_status_available ON couriers (status, is_available);
CREATE INDEX idx_couriers_deleted_at       ON couriers (deleted_at);

-- ─── COURIER LOCATIONS ────────────────────────────────────────────────────────
-- Full GPS history per courier. Consider range-partitioning by recorded_at and
-- a TTL policy (e.g. purge rows older than 90 days) once table grows large.
CREATE TABLE courier_locations
(
    id              UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    courier_id      UUID          NOT NULL REFERENCES couriers (id),
    latitude        NUMERIC(10,7) NOT NULL,
    longitude       NUMERIC(10,7) NOT NULL,
    heading         NUMERIC(10,7),
    speed           NUMERIC(10,2),
    accuracy_meters NUMERIC(8,2),
    recorded_at     TIMESTAMPTZ   NOT NULL
);

-- Dominant query: latest ping for a specific courier
CREATE INDEX idx_courier_locations_courier_recorded ON courier_locations (courier_id, recorded_at DESC);
CREATE INDEX idx_courier_locations_recorded_at      ON courier_locations (recorded_at);

-- ─── DELIVERY ZONES ───────────────────────────────────────────────────────────
CREATE TABLE delivery_zones
(
    id                        UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    zone_code                 VARCHAR(50)  NOT NULL,
    name                      VARCHAR(255) NOT NULL,
    max_delivery_time_minutes INTEGER,
    max_distance_km           NUMERIC(5,2),
    -- PostGIS geography — spatial queries via ST_Contains, ST_DWithin, etc.
    polygon                   GEOGRAPHY(POLYGON, 4326),
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_delivery_zones_zone_code UNIQUE (zone_code)
);

CREATE INDEX idx_delivery_zones_is_active ON delivery_zones (is_active);
-- GIST index required for spatial operators — cannot be created via JPA @Index
CREATE INDEX idx_delivery_zones_polygon ON delivery_zones USING GIST (polygon);

-- ─── DELIVERY ORDERS ──────────────────────────────────────────────────────────
CREATE TABLE delivery_orders
(
    id                      UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    version                 BIGINT        NOT NULL DEFAULT 0,
    ecommerce_order_id      UUID          NOT NULL,
    ecommerce_store_id      UUID          NOT NULL,
    customer_name           VARCHAR(255)  NOT NULL,
    customer_phone          VARCHAR(50)   NOT NULL,
    pickup_address          TEXT          NOT NULL,
    pickup_lat              NUMERIC(10,7) NOT NULL,
    pickup_lng              NUMERIC(10,7) NOT NULL,
    dropoff_address         TEXT          NOT NULL,
    dropoff_lat             NUMERIC(10,7) NOT NULL,
    dropoff_lng             NUMERIC(10,7) NOT NULL,
    package_size            VARCHAR(30),
    package_weight          NUMERIC(10,2),
    delivery_fee            NUMERIC(10,2),
    priority                VARCHAR(30)   NOT NULL,
    status                  VARCHAR(50)   NOT NULL,
    -- Denormalised FK — avoids joining courier_assignments on every order read
    assigned_courier_id     UUID          REFERENCES couriers (id),
    estimated_delivery_time TIMESTAMPTZ,
    actual_delivery_time    TIMESTAMPTZ,
    cancelled_reason        TEXT,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_orders_status           ON delivery_orders (status);
CREATE INDEX idx_delivery_orders_ecommerce_order  ON delivery_orders (ecommerce_order_id);
CREATE INDEX idx_delivery_orders_ecommerce_store  ON delivery_orders (ecommerce_store_id);
CREATE INDEX idx_delivery_orders_assigned_courier ON delivery_orders (assigned_courier_id);
CREATE INDEX idx_delivery_orders_created_at       ON delivery_orders (created_at);
CREATE INDEX idx_delivery_orders_status_created   ON delivery_orders (status, created_at);

-- ─── COURIER ASSIGNMENTS ──────────────────────────────────────────────────────
CREATE TABLE courier_assignments
(
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    delivery_id      UUID        NOT NULL REFERENCES delivery_orders (id),
    courier_id       UUID        NOT NULL REFERENCES couriers (id),
    status           VARCHAR(30) NOT NULL,
    attempt_number   INTEGER     NOT NULL DEFAULT 1,
    assigned_at      TIMESTAMPTZ NOT NULL,
    accepted_at      TIMESTAMPTZ,
    rejected_at      TIMESTAMPTZ,
    rejection_reason TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_courier_assignments_delivery       ON courier_assignments (delivery_id);
CREATE INDEX idx_courier_assignments_courier        ON courier_assignments (courier_id);
CREATE INDEX idx_courier_assignments_status         ON courier_assignments (status);
CREATE INDEX idx_courier_assignments_courier_status ON courier_assignments (courier_id, status);

-- ─── DELIVERY STATUS HISTORY ──────────────────────────────────────────────────
CREATE TABLE delivery_status_history
(
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    delivery_id UUID        NOT NULL REFERENCES delivery_orders (id),
    status      VARCHAR(50) NOT NULL,
    latitude    NUMERIC(10,7),
    longitude   NUMERIC(10,7),
    -- Who triggered the transition: COURIER | SYSTEM | OPS
    changed_by  VARCHAR(50),
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_status_history_delivery_created ON delivery_status_history (delivery_id, created_at);

-- ─── DELIVERY TRACKING ────────────────────────────────────────────────────────
-- Customer-facing position trail scoped to a single delivery window.
-- Range-partition by recorded_at and purge post-delivery rows older than 30 days.
CREATE TABLE delivery_tracking
(
    id          UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    delivery_id UUID          NOT NULL REFERENCES delivery_orders (id),
    courier_lat NUMERIC(10,7) NOT NULL,
    courier_lng NUMERIC(10,7) NOT NULL,
    heading     NUMERIC(10,7),
    speed       NUMERIC(10,2),
    recorded_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_delivery_tracking_delivery_recorded ON delivery_tracking (delivery_id, recorded_at DESC);

-- ─── DELIVERY PROOFS ──────────────────────────────────────────────────────────
CREATE TABLE delivery_proofs
(
    id            UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    delivery_id   UUID         NOT NULL REFERENCES delivery_orders (id),
    image_url     TEXT,
    signature_url TEXT,
    delivered_to  VARCHAR(255),
    photo_taken_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_delivery_proofs_delivery UNIQUE (delivery_id)
);

CREATE INDEX idx_delivery_proofs_delivery ON delivery_proofs (delivery_id);

-- ─── DELIVERY PRICING RULES ───────────────────────────────────────────────────
CREATE TABLE delivery_pricing_rules
(
    id                  UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    -- NULL zone_id = global rule; non-NULL = zone-specific override
    zone_id             UUID          REFERENCES delivery_zones (id),
    -- NULL vehicle_type = applies to all vehicles
    vehicle_type        VARCHAR(50),
    base_price          NUMERIC(10,2) NOT NULL,
    price_per_km        NUMERIC(10,2) NOT NULL,
    min_fee             NUMERIC(10,2) NOT NULL,
    max_distance_km     NUMERIC(5,2),
    priority_multiplier NUMERIC(5,2),
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
    effective_from      TIMESTAMPTZ,
    effective_to        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_pricing_rules_is_active           ON delivery_pricing_rules (is_active);
CREATE INDEX idx_pricing_rules_zone_id             ON delivery_pricing_rules (zone_id);
CREATE INDEX idx_pricing_rules_vehicle_type        ON delivery_pricing_rules (vehicle_type);
CREATE INDEX idx_pricing_rules_active_zone_vehicle ON delivery_pricing_rules (is_active, zone_id, vehicle_type);
CREATE INDEX idx_pricing_rules_effective_range     ON delivery_pricing_rules (effective_from, effective_to);

-- ─── COURIER EARNINGS ─────────────────────────────────────────────────────────
CREATE TABLE courier_earnings
(
    id             UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    courier_id     UUID          NOT NULL REFERENCES couriers (id),
    delivery_id    UUID          NOT NULL REFERENCES delivery_orders (id),
    earning_type   VARCHAR(30)   NOT NULL,
    earning_amount NUMERIC(10,2) NOT NULL,
    payout_status  VARCHAR(30)   NOT NULL,
    payout_date    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_courier_earnings_courier_created ON courier_earnings (courier_id, created_at);
CREATE INDEX idx_courier_earnings_payout_status   ON courier_earnings (payout_status);
CREATE INDEX idx_courier_earnings_courier_payout  ON courier_earnings (courier_id, payout_status);
CREATE INDEX idx_courier_earnings_delivery        ON courier_earnings (delivery_id);
