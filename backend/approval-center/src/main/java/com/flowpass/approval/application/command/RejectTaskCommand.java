package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

/**
 * Command to reject one task of an approval run.
 *
 * @param tenantId tenant scope
 * @param taskId   id of the task being rejected
 * @param actor    the authenticated caller (from {@code CurrentActorPort}, never the request body)
 * @param comment  optional rejection comment recorded on the audit ledger
 */
public record RejectTaskCommand(TenantId tenantId, String taskId, ApproverRef actor, String comment) {
}
