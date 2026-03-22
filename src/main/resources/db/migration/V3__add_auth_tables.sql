-- ─────────────────────────────────────────────────────────────────────────────
-- V3 — Courier authentication tables
-- ─────────────────────────────────────────────────────────────────────────────
-- Adds three tables to support self-contained courier authentication:
--
--   courier_credentials    — phone + bcrypt-hashed password, account lockout
--   courier_vehicle_details — extended vehicle info; driving licence required
--                             only for motorized vehicles (SCOOTER, CAR)
--   courier_refresh_tokens — hashed JWT refresh token store
--
-- Signup is admin-only (enforced at the API layer via ROLE_ADMIN JWT claim).
-- Login uses phone number + password; issues short-lived access JWT + long-lived
-- refresh token.
-- ─────────────────────────────────────────────────────────────────────────────

-- ─── COURIER CREDENTIALS ──────────────────────────────────────────────────────
-- One credential record per courier. Created by an admin during onboarding.
-- Phone number is the login identifier (already unique in `couriers`, mirrored
-- here to avoid a join on every authentication attempt).
--
-- Account status lifecycle:
--   PENDING_ACTIVATION → courier registered but has not set their own password
--   ACTIVE             → can log in normally
--   LOCKED             → temporarily locked after too many failed attempts
--   SUSPENDED          → admin-suspended; cannot log in until admin reinstates

CREATE TABLE courier_credentials
(
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,

    -- 1-to-1 link to the courier profile
    courier_id            UUID         NOT NULL REFERENCES couriers (id),

    -- Login identifier — mirrors couriers.phone; kept here to avoid join on auth hot path
    phone_number          VARCHAR(30)  NOT NULL,

    -- BCrypt-hashed password (strength ≥ 12). Never stored in plain text.
    password_hash         VARCHAR(255) NOT NULL,

    -- Current state of the account
    account_status        VARCHAR(30)  NOT NULL DEFAULT 'PENDING_ACTIVATION',

    -- Brute-force protection: incremented on each failed attempt; reset on success
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,

    -- If non-NULL, login is blocked until this timestamp (temporary lockout)
    locked_until          TIMESTAMPTZ,

    -- Informational audit fields
    last_login_at         TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ,

    -- UUID of the admin who created this courier account (for audit trail)
    -- Not a FK because admin users live in a separate service/Keycloak realm
    created_by_admin_id   UUID,

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_courier_credentials_courier_id UNIQUE (courier_id),
    CONSTRAINT uk_courier_credentials_phone      UNIQUE (phone_number),
    CONSTRAINT chk_courier_credentials_status    CHECK (
        account_status IN ('PENDING_ACTIVATION', 'ACTIVE', 'LOCKED', 'SUSPENDED')
    )
);

CREATE INDEX idx_courier_credentials_courier_id ON courier_credentials (courier_id);
-- Phone index: primary lookup path during login
CREATE INDEX idx_courier_credentials_phone      ON courier_credentials (phone_number);
CREATE INDEX idx_courier_credentials_status     ON courier_credentials (account_status);


-- ─── COURIER VEHICLE DETAILS ──────────────────────────────────────────────────
-- Extended vehicle information collected at signup. Driving licence fields
-- are conditionally required based on vehicle_type:
--
--   BICYCLE, FOOT  → requires_driving_license = FALSE; licence fields must be NULL
--   SCOOTER, CAR   → requires_driving_license = TRUE;  licence fields must be present
--
-- The CHECK constraint enforces this rule at the database level. The application
-- layer must also validate and set requires_driving_license before inserting.
--
-- license_plate is UNIQUE to prevent the same physical vehicle being registered
-- for multiple couriers; it is NULL for BICYCLE and FOOT couriers.

CREATE TABLE courier_vehicle_details
(
    id                        UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,

    -- 1-to-1 link to the courier profile
    courier_id                UUID        NOT NULL REFERENCES couriers (id),

    -- Mirrors couriers.vehicle_type to keep vehicle details self-contained
    vehicle_type              VARCHAR(30) NOT NULL,

    -- Physical vehicle identity (NULL for BICYCLE / FOOT — no registered vehicle)
    vehicle_make              VARCHAR(100),           -- e.g. Honda, Toyota
    vehicle_model             VARCHAR(100),           -- e.g. CBR500R, Corolla
    vehicle_year              SMALLINT,               -- e.g. 2022
    vehicle_color             VARCHAR(50),            -- e.g. Black
    license_plate             VARCHAR(50),            -- e.g. 10 AA 123 (unique when present)
    vehicle_registration_url  TEXT,                   -- URL to scanned registration document

    -- ── Driving licence (SCOOTER / CAR only) ──────────────────────────────────
    driving_license_number    VARCHAR(100),           -- Official licence number
    driving_license_expiry    DATE,                   -- Must be a future date at signup
    driving_license_front_url TEXT,                   -- URL to front scan of licence card
    driving_license_back_url  TEXT,                   -- URL to back scan of licence card

    -- Application sets this flag at insertion based on vehicle_type.
    -- The CHECK below ties it to the presence/absence of licence fields.
    requires_driving_license  BOOLEAN     NOT NULL,

    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uk_courier_vehicle_details_courier_id   UNIQUE (courier_id),
    -- Prevent same physical vehicle being assigned to multiple couriers
    CONSTRAINT uk_courier_vehicle_details_license_plate UNIQUE (license_plate),

    -- Driving licence completeness rule:
    --   Motorized (requires_driving_license = TRUE)  → number + expiry must be set
    --   Non-motorized (requires_driving_license = FALSE) → number + expiry must be NULL
    CONSTRAINT chk_vehicle_driving_license CHECK (
        (requires_driving_license = TRUE
             AND driving_license_number IS NOT NULL
             AND driving_license_expiry IS NOT NULL)
        OR
        (requires_driving_license = FALSE
             AND driving_license_number IS NULL
             AND driving_license_expiry IS NULL)
    ),

    -- Motorized vehicles must have a license plate
    CONSTRAINT chk_vehicle_plate_required CHECK (
        requires_driving_license = FALSE OR license_plate IS NOT NULL
    ),

    CONSTRAINT chk_vehicle_type CHECK (
        vehicle_type IN ('BICYCLE', 'FOOT', 'SCOOTER', 'CAR')
    )
);

CREATE INDEX idx_vehicle_details_courier_id   ON courier_vehicle_details (courier_id);
CREATE INDEX idx_vehicle_details_vehicle_type ON courier_vehicle_details (vehicle_type);
CREATE INDEX idx_vehicle_details_license_plate ON courier_vehicle_details (license_plate)
    WHERE license_plate IS NOT NULL;


-- ─── COURIER REFRESH TOKENS ───────────────────────────────────────────────────
-- Stores SHA-256 hashes of issued refresh tokens (never the raw token itself).
-- Each successful login creates a new row. Logout or token rotation revokes the
-- current row by setting revoked_at. Expired / revoked tokens should be purged
-- periodically by a maintenance job.
--
-- Flow:
--   1. Login  → issue refresh token R; store SHA-256(R) here; return R to client
--   2. Refresh → client sends R; compute SHA-256(R); look up by token_hash;
--               verify not revoked and not expired; issue new access JWT
--   3. Logout  → set revoked_at = now() on the token row

CREATE TABLE courier_refresh_tokens
(
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,

    -- Owning courier
    courier_id  UUID         NOT NULL REFERENCES couriers (id),

    -- SHA-256 hex digest of the raw refresh token (64 hex chars = 256-bit)
    token_hash  VARCHAR(64)  NOT NULL,

    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,               -- e.g. now() + INTERVAL '30 days'

    -- NULL = token is still valid; non-NULL = explicitly revoked (logout / rotation)
    revoked_at  TIMESTAMPTZ,

    -- Optional audit context stored at issuance
    device_info VARCHAR(255),                        -- mobile app version / browser UA
    ip_address  VARCHAR(45),                         -- client IP at login time (IPv4/IPv6)

    CONSTRAINT uk_courier_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_courier_id ON courier_refresh_tokens (courier_id);
-- Fast lookup by token hash on every refresh request
CREATE INDEX idx_refresh_tokens_token_hash ON courier_refresh_tokens (token_hash);
-- Used by maintenance job to delete expired tokens
CREATE INDEX idx_refresh_tokens_expires_at ON courier_refresh_tokens (expires_at);
-- Partial index covering only live (non-revoked) tokens — most frequent query
CREATE INDEX idx_refresh_tokens_active     ON courier_refresh_tokens (courier_id, expires_at)
    WHERE revoked_at IS NULL;
