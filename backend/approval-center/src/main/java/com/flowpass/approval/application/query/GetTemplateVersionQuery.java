package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;

/** Query for one published version of a template, with its definition. */
public record GetTemplateVersionQuery(TenantId tenantId, String templateKey, int version) {
}
