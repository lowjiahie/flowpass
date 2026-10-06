package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

/**
 * Command to approve one task of an approval run.
 *
 * @param tenantId tenant scope
 * @param taskId   id of the task being approved
 * @param actor    the authenticated caller (from {@code CurrentActorPort}, never the request body)
 * @param comment  optional approval comment recorded on the audit ledger
 */
public record ApproveTaskCommand(TenantId tenantId, String taskId, ApproverRef actor, String comment) {
}
