package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.TenantId;

import java.util.Map;

/** Command to replace a template's draft form schema; a {@code null} schema removes it. */
public record SaveTemplateFormSchemaCommand(TenantId tenantId, String templateKey, Map<String, Object> schema) {
}
