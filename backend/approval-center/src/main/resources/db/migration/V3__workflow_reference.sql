-- ============================================================================
-- V3: tenant-scoped mapping between a platform approval instance and the
--     process-engine local reference (e.g. Flowable processInstanceId).
-- The engine id is an infrastructure concern (charter §4.3): it is kept here in
-- the adapter-owned mapping and is never exposed as the public instance id.
-- ============================================================================

CREATE TABLE approval_workflow_ref (
    instance_id         VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    definition_key      VARCHAR(64)  NOT NULL,
    definition_version  INT          NOT NULL,
    process_instance_id VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_workflow_ref_process UNIQUE (process_instance_id)
);

CREATE INDEX idx_workflow_ref_tenant ON approval_workflow_ref (tenant_id);
