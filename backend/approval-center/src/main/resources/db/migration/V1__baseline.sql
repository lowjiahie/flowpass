-- ============================================================================
-- V1: initial schema baseline for the approval service (Phase-1).
-- Conventions:
--   * All business tables carry a trusted `tenant_id` (shared-database scope).
--   * All times stored in UTC (timestamptz).
--   * Money is `numeric` + `currency` (never float).
--   * State changes use optimistic `version` (lock_version) for concurrency.
-- ============================================================================

CREATE TABLE approval_template (
    id            VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    template_key  VARCHAR(64)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_template_tenant_key UNIQUE (tenant_id, template_key)
);

CREATE TABLE process_definition (
    id            VARCHAR(64)  PRIMARY KEY,
    template_id   VARCHAR(64)  NOT NULL REFERENCES approval_template (id),
    version       INT          NOT NULL,
    bpmn_xml      TEXT         NOT NULL,
    checksum      VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_definition_template_version UNIQUE (template_id, version)
);

CREATE TABLE form_schema (
    id           VARCHAR(64) PRIMARY KEY,
    template_id  VARCHAR(64) NOT NULL REFERENCES approval_template (id),
    version      INT         NOT NULL,
    json_schema  TEXT        NOT NULL,
    ui_schema    TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_form_template_version UNIQUE (template_id, version)
);

CREATE TABLE approval_instance (
    id                 VARCHAR(64)   PRIMARY KEY,
    tenant_id          VARCHAR(64)   NOT NULL,
    application_id     VARCHAR(64)   NOT NULL,
    business_key       VARCHAR(128)  NOT NULL,
    template_key       VARCHAR(64)   NOT NULL,
    initiator_type     VARCHAR(16)   NOT NULL,
    initiator_id       VARCHAR(128)  NOT NULL,
    amount             NUMERIC(18, 2),
    currency           VARCHAR(8),
    definition_version INT           NOT NULL,
    status             VARCHAR(32)   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_instance_tenant_app_business UNIQUE (tenant_id, application_id, business_key)
);

CREATE INDEX idx_instance_tenant_status ON approval_instance (tenant_id, status);

CREATE TABLE approval_task (
    id            VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    instance_id   VARCHAR(64)  NOT NULL REFERENCES approval_instance (id),
    name          VARCHAR(128) NOT NULL,
    assignee_type VARCHAR(16),
    assignee_id   VARCHAR(128),
    status        VARCHAR(32)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_tenant_status ON approval_task (tenant_id, status);
CREATE INDEX idx_task_instance ON approval_task (instance_id);

CREATE TABLE approval_action (
    id          VARCHAR(64)  PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    instance_id VARCHAR(64)  NOT NULL,
    task_id     VARCHAR(64),
    actor_type  VARCHAR(16)  NOT NULL,
    actor_id    VARCHAR(128) NOT NULL,
    action      VARCHAR(32)  NOT NULL,
    comment     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_action_instance ON approval_action (instance_id);

CREATE TABLE decision_record (
    id           VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    instance_id  VARCHAR(64),
    rule_id      VARCHAR(64)  NOT NULL,
    rule_version INT          NOT NULL,
    input_hash   VARCHAR(128) NOT NULL,
    result       TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE outbox_event (
    id             VARCHAR(64)   PRIMARY KEY,
    event_id       VARCHAR(64)   NOT NULL,
    tenant_id      VARCHAR(64)   NOT NULL,
    aggregate_id   VARCHAR(64)   NOT NULL,
    aggregate_type VARCHAR(64)   NOT NULL,
    type           VARCHAR(128)  NOT NULL,
    payload        JSONB         NOT NULL,
    publish_status VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_status ON outbox_event (publish_status);

CREATE TABLE webhook_delivery (
    id           VARCHAR(64)  PRIMARY KEY,
    event_id     VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    endpoint     VARCHAR(512) NOT NULL,
    attempt      INT          NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL,
    next_retry_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_record (
    id              VARCHAR(64)  PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    application_id  VARCHAR(64)  NOT NULL,
    idem_key        VARCHAR(128) NOT NULL,
    response_ref    VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_idempotency UNIQUE (tenant_id, application_id, idem_key)
);
