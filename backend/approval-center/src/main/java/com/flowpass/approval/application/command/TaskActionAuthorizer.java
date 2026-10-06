package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.directory.OrgDirectory;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskAuthorizationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Resource-level authorization for acting on a task, shared by approve and reject.
 *
 * <p>Combines the domain policy ({@link TaskAuthorizationPolicy}: assignee, or holder of the assigned role) with
 * the one rule that needs the instance: nobody approves or rejects their own request through a role — the
 * initiator may be a member of the approving role, but that must not let them sign off themselves.</p>
 */
@Component
public class TaskActionAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(TaskActionAuthorizer.class);

    private final OrgDirectory orgDirectory;

    public TaskActionAuthorizer(OrgDirectory orgDirectory) {
        this.orgDirectory = orgDirectory;
    }

    /**
     * @throws BusinessException {@code FORBIDDEN} when {@code actor} may not {@code action} the task
     */
    public void requireCanAct(ApprovalTask task, ApprovalInstance instance, ApproverRef actor, String action) {
        boolean roleTask = TaskAuthorizationPolicy.isRoleTask(task);
        Set<String> roles = roleTask && "USER".equals(actor.type())
                ? orgDirectory.rolesOf(task.tenantId(), actor.id())
                : Set.of();

        if (!TaskAuthorizationPolicy.canAct(task, actor, roles)) {
            log.warn("Denied {} on task {} (tenant {}) for actor {}:{} — not the assignee",
                    action, task.id(), task.tenantId().value(), actor.type(), actor.id());
            throw new BusinessException(BusinessErrorCode.FORBIDDEN, "Not the assignee of task " + task.id());
        }
        if (roleTask && instance.isInitiatedBy(actor)) {
            log.warn("Denied {} on task {} (tenant {}) for actor {}:{} — initiator acting through a role",
                    action, task.id(), task.tenantId().value(), actor.type(), actor.id());
            throw new BusinessException(BusinessErrorCode.FORBIDDEN,
                    "The initiator cannot " + action + " their own request through a role");
        }
    }
}
