package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;

/** Query for the application form of a template's latest published version. */
public record GetPublishedFormQuery(TenantId tenantId, String templateKey) {
}
