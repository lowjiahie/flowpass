package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

/**
 * Command to withdraw a running approval instance (initiator only).
 *
 * @param tenantId   tenant scope
 * @param instanceId platform instance id
 * @param actor      the authenticated caller (from {@code CurrentActorPort}, never the request body)
 * @param comment    optional reason recorded on the audit ledger
 */
public record WithdrawInstanceCommand(TenantId tenantId, String instanceId, ApproverRef actor, String comment) {
}
