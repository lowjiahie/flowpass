package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.TenantId;

/** Command to disable ({@code enabled=false}) or re-enable a published template. */
public record SetTemplateEnabledCommand(TenantId tenantId, String templateKey, boolean enabled) {
}
