package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.TenantId;

/**
 * Command to register a new approval template (created in {@code DRAFT}).
 *
 * <p>The tenant id is trusted (resolved server-side). The template key must correspond to a classpath
 * BPMN definition for the template to be publishable.</p>
 */
public record CreateTemplateCommand(TenantId tenantId, String templateKey) {
}
