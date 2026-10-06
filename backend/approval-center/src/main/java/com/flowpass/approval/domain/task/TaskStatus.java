package com.flowpass.approval.domain.task;

/**
 * Lifecycle of an approval task.
 *
 * <p>Matches the charter + appendix B statuses. Transitions are enforced on the aggregate (a task can
 * be claimed, approved or rejected; M2 wires those use cases).</p>
 */
public enum TaskStatus {
    PENDING,
    CLAIMED,
    APPROVED,
    REJECTED,
    TRANSFERRED,
    CANCELLED
}
