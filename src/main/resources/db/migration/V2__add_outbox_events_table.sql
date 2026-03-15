-- ─────────────────────────────────────────────────────────────────────────────
-- V2 — Transactional Outbox for at-least-once event delivery
--
-- Events are written to this table in the SAME transaction as the domain write.
-- A background job (OutboxPublisherJob) polls PENDING rows and publishes them to
-- RabbitMQ, then marks them PUBLISHED. If the broker is down, the row stays PENDING
-- and will be retried. This guarantees no event is silently dropped on broker failure.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE outbox_events
(
    id            UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    routing_key   VARCHAR(100) NOT NULL,
    -- Full JSON payload of the event — includes all fields needed by consumers
    payload       TEXT         NOT NULL,
    -- Monotonically increasing version — consumers use this to detect schema changes
    event_version INT          NOT NULL DEFAULT 1,
    -- PENDING → PUBLISHED (or FAILED after max retries)
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count   INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

-- Primary query: pick up PENDING events in insertion order
CREATE INDEX idx_outbox_events_status_created ON outbox_events (status, created_at)
    WHERE status = 'PENDING';
