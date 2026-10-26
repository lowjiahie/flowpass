package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

/**
 * Command to hand an open task to another user.
 *
 * @param actor  the authenticated caller (from {@code CurrentActorPort}, never the request body)
 * @param toUser the user who takes the task over
 */
public record TransferTaskCommand(TenantId tenantId, String taskId, ApproverRef actor, String toUser, String comment) {
}
