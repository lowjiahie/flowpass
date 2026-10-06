package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;

/** Read query for listing approval-matrix rules, optionally scoped to a template key. */
public record ListDecisionRulesQuery(TenantId tenantId, String templateKey) {
}
