-- ============================================================================
-- V6: link each approval task to the process-engine step that produced it.
-- The engine is the source of truth for *which* tasks exist (it routes the token); the platform task
-- is the business view of one engine user task. `workflow_task_ref` is an opaque, adapter-issued
-- reference (e.g. a Flowable task id) — never exposed through the public API (charter §4.3).
-- `node_key` is the process node (BPMN element id) the task belongs to.
-- ============================================================================

ALTER TABLE approval_task
    ADD COLUMN node_key          VARCHAR(64),
    ADD COLUMN workflow_task_ref VARCHAR(64);

CREATE UNIQUE INDEX uk_task_workflow_ref
    ON approval_task (tenant_id, workflow_task_ref)
    WHERE workflow_task_ref IS NOT NULL;
