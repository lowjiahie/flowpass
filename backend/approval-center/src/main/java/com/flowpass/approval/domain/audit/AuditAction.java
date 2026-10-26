package com.flowpass.approval.domain.audit;

/**
 * Business action recorded by an audit entry.
 *
 * <p>Past-tense verbs, distinct from the status enums of the workflow aggregates. Persisted as the
 * action discriminator in {@code approval_action}. Extend as new approvals/management actions are
 * added; an existing value is never renamed (append-only ledger).</p>
 */
public enum AuditAction {
    STARTED,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    TRANSFERRED,
    APPROVER_ADDED,
    RETURNED,
    DELEGATED,
    OVERDUE,
    ESCALATED
}
