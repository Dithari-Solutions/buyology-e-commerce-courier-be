-- ─────────────────────────────────────────────────────────────────────────────
-- V4 — Admin audit log
-- ─────────────────────────────────────────────────────────────────────────────
-- Records every write action performed by an admin from the main buyology app.
-- Because the admin identity lives in Keycloak (external), capturing it here
-- is the only forensic trail this service owns.
--
-- Immutable by design: no UPDATE or DELETE ever issued against this table.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE admin_audit_log
(
    id           UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,

    -- Admin identity from the Keycloak JWT sub claim
    admin_id     UUID         NOT NULL,
    -- Admin email from the JWT email claim (snapshot at time of action)
    admin_email  VARCHAR(255),

    -- What was done — see AdminAction enum
    action       VARCHAR(100) NOT NULL,

    -- The primary resource affected (e.g. the new courier's UUID)
    resource_id  UUID,

    -- Optional JSON blob with extra context (e.g. {"vehicleType":"SCOOTER"})
    details      TEXT,

    -- Network context captured at request time
    ip_address   VARCHAR(45),
    user_agent   VARCHAR(500),

    -- Immutable timestamp
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Lookup by admin — who did what?
CREATE INDEX idx_admin_audit_log_admin_id    ON admin_audit_log (admin_id);
-- Lookup by resource — what happened to this courier?
CREATE INDEX idx_admin_audit_log_resource_id ON admin_audit_log (resource_id);
-- Time-range queries for compliance reports
CREATE INDEX idx_admin_audit_log_created_at  ON admin_audit_log (created_at);
-- Combined: admin + time window
CREATE INDEX idx_admin_audit_log_admin_time  ON admin_audit_log (admin_id, created_at);
