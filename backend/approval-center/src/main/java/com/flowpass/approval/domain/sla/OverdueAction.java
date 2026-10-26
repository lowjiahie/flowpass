package com.flowpass.approval.domain.sla;

/** What happens when a task passes its deadline. An {@code ApprovalTaskOverdue} event is always emitted. */
public enum OverdueAction {
    /** Only notify (event + audit); the task stays where it is. */
    NOTIFY_ONLY,
    /** Hand the task to the assignee's manager (falls back to the policy's user when there is none). */
    ESCALATE_TO_MANAGER,
    /** Hand the task to the policy's named user. */
    ESCALATE_TO_USER
}
