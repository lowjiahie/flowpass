package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.TenantId;

/** Command to replace a template's editable draft definition. */
public record SaveTemplateDraftCommand(TenantId tenantId, String templateKey, String bpmnXml) {
}
