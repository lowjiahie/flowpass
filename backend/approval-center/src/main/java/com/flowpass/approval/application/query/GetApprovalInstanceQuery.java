package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.runtime.ApprovalId;

/** Query object for fetching a single approval instance. */
public record GetApprovalInstanceQuery(TenantId tenantId, ApprovalId instanceId) {
}
