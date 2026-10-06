package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;

/** Query for the published versions of a template. */
public record ListTemplateVersionsQuery(TenantId tenantId, String templateKey) {
}
