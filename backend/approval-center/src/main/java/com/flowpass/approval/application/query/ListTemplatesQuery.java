package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;

/** Read query for listing all templates of a tenant. */
public record ListTemplatesQuery(TenantId tenantId) {
}
