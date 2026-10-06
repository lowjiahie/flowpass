package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

import java.util.Map;

/** Read query: which approvers would the approval matrix resolve for these variables (dry run). */
public record PreviewApproversQuery(TenantId tenantId, String templateKey, ApproverRef initiator,
                                    Map<String, Object> variables) {
}
