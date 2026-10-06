package com.flowpass.approval.domain.task;

import com.flowpass.approval.domain.common.ApproverRef;

import java.util.Set;

/**
 * Domain policy: who may act on (approve / reject) a task.
 *
 * <p>Pure business rule, free of any identity-provider or framework type. A task assigned to a user can be
 * acted on by that user; a task assigned to a {@code ROLE} by any user who currently holds the role (the first
 * to act wins — the task lifecycle makes the second attempt fail). Delegation extends this method without
 * changing its signature.</p>
 */
public final class TaskAuthorizationPolicy {

    public static final String ROLE = "ROLE";
    private static final String USER = "USER";

    private TaskAuthorizationPolicy() {
    }

    /** True when {@code actor} may act on {@code task}, given only a direct assignment (no roles known). */
    public static boolean canAct(ApprovalTask task, ApproverRef actor) {
        return canAct(task, actor, Set.of());
    }

    /**
     * True when {@code actor}, who currently holds {@code actorRoles}, may act on {@code task}. A task without
     * an assignee is never actionable.
     */
    public static boolean canAct(ApprovalTask task, ApproverRef actor, Set<String> actorRoles) {
        ApproverRef assignee = task.assignee();
        if (assignee == null) {
            return false;
        }
        if (ROLE.equals(assignee.type())) {
            return USER.equals(actor.type()) && actorRoles.contains(assignee.id());
        }
        return assignee.equals(actor);
    }

    /** True when the task is assigned to a role rather than to one user. */
    public static boolean isRoleTask(ApprovalTask task) {
        return task.assignee() != null && ROLE.equals(task.assignee().type());
    }
}
