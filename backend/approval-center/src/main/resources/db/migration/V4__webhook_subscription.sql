-- ============================================================================
-- V4: webhook subscription registry for the outbox dispatcher.
-- Conventions (same as V1): trusted tenant_id, UTC timestamps.
--
--   * One row = one (tenant, event_type) → endpoint mapping that the outbox
--     dispatcher uses to resolve where a PENDING outbox event should be delivered.
--   * An `enabled` flag allows operators to pause a subscription without deleting
--     it (outbox events for a disabled subscription are skipped, not queued).
-- ============================================================================

CREATE TABLE webhook_subscription (
    id         VARCHAR(64)  PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    endpoint   VARCHAR(512) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_subscription_tenant_event UNIQUE (tenant_id, event_type)
);

CREATE INDEX idx_subscription_tenant ON webhook_subscription (tenant_id);
