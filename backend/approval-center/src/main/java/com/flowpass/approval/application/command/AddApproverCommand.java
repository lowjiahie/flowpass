package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

/**
 * Command to ask another user to approve as well (parallel add-sign).
 *
 * @param actor  the authenticated caller (from {@code CurrentActorPort}, never the request body)
 * @param userId the user added to the task's approval round
 */
public record AddApproverCommand(TenantId tenantId, String taskId, ApproverRef actor, String userId, String comment) {
}
