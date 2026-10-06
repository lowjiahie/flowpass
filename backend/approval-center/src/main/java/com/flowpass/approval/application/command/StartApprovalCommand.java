package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

import java.util.Map;

/**
 * Command to start a new approval instance (the POST /v1/approval-instances write path).
 *
 * <p>Carries everything a use case needs, already resolved from the inbound request. The tenant id is
 * trusted (verified server-side). {@code actor} is the authenticated caller from {@code CurrentActorPort};
 * {@code onBehalfOf} is the optional initiator the caller asks to act for — honoured only for a
 * {@code SYSTEM} actor (a business system starting an approval for one of its users), see
 * {@link StartApprovalHandler}. Neither is a raw client-submitted security decision.</p>
 */
public record StartApprovalCommand(
        TenantId tenantId,
        String applicationId,
        String templateKey,
        String externalBusinessKey,
        String idempotencyKey,
        ApproverRef actor,
        ApproverRef onBehalfOf,
        Map<String, Object> variables,
        Map<String, Object> callbackContext) {
}
