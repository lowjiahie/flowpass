package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;

/** Query for one template with its draft. */
public record GetTemplateQuery(TenantId tenantId, String templateKey) {
}
