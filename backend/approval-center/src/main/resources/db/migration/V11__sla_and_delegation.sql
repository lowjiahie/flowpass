-- ============================================================================
-- V11: task deadlines (SLA) and delegation.
--
-- sla_policy: per template, how long a task may stay open, when to remind, and what to do when it is overdue.
--   A policy only affects tasks created after it is saved (each task snapshots its own deadlines).
-- approval_task: due_at / remind_at are set when the task is created; reminded_at / overdue_handled_at are
--   claimed with a conditional UPDATE, so a reminder or an escalation happens once even with several nodes.
-- delegation: "while I am away, my new tasks go to X" for a bounded period.
-- ============================================================================

CREATE TABLE sla_policy (
    tenant_id           VARCHAR(64)  NOT NULL,
    template_key        VARCHAR(64)  NOT NULL,
    due_after_hours     INT          NOT NULL CHECK (due_after_hours BETWEEN 1 AND 8760),
    remind_before_hours INT          CHECK (remind_before_hours BETWEEN 1 AND 8760),
    on_overdue          VARCHAR(32)  NOT NULL,
    escalate_to_user    VARCHAR(128),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, template_key)
);

ALTER TABLE approval_task
    ADD COLUMN due_at             TIMESTAMPTZ,
    ADD COLUMN remind_at          TIMESTAMPTZ,
    ADD COLUMN reminded_at        TIMESTAMPTZ,
    ADD COLUMN overdue_handled_at TIMESTAMPTZ;

CREATE INDEX idx_task_to_remind ON approval_task (remind_at)
    WHERE status IN ('PENDING', 'CLAIMED') AND remind_at IS NOT NULL AND reminded_at IS NULL;
CREATE INDEX idx_task_overdue ON approval_task (due_at)
    WHERE status IN ('PENDING', 'CLAIMED') AND due_at IS NOT NULL AND overdue_handled_at IS NULL;

CREATE TABLE delegation (
    id           VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    delegator_id VARCHAR(128) NOT NULL,
    delegate_id  VARCHAR(128) NOT NULL,
    starts_at    TIMESTAMPTZ  NOT NULL,
    ends_at      TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ,
    CHECK (ends_at > starts_at)
);

CREATE INDEX idx_delegation_delegator ON delegation (tenant_id, delegator_id, ends_at) WHERE revoked_at IS NULL;
