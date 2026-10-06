-- ============================================================================
-- V5: decision rules (approval matrix).
-- One row = one deterministic rule for a (tenant, template_key). When its
-- structured condition matches an instance's variables, its approvers become the
-- first-round candidates. Rules are evaluated by ascending `priority`; the first
-- match wins (no expression engine — structured, deterministic).
-- Conventions (same as V1): trusted tenant_id, UTC timestamps.
--
--   * amount_min / amount_max: optional band, `amount_min <= amount < amount_max`.
--   * field_matches: optional JSONB object of {field: expectedString}; every
--     entry must equal the instance variable's string value.
--   * approvers: JSONB array of {type, id}; at least one is required.
-- ============================================================================

CREATE TABLE decision_rule (
    id            VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    template_key  VARCHAR(64)  NOT NULL,
    priority      INT          NOT NULL,
    amount_min    NUMERIC(18, 2),
    amount_max    NUMERIC(18, 2),
    field_matches JSONB,
    approvers     JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_decision_rule_tenant_template ON decision_rule (tenant_id, template_key, priority);
