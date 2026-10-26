package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

/**
 * Command to send a request back to the previous approval round.
 *
 * @param actor the authenticated caller (from {@code CurrentActorPort}, never the request body)
 */
public record ReturnTaskCommand(TenantId tenantId, String taskId, ApproverRef actor, String comment) {
}
