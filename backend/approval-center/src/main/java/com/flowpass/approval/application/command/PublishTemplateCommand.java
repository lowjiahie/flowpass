package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

/**
 * Command to publish a template: snapshot its definition as the next immutable version and make it runnable.
 *
 * @param actor the authenticated publisher (from {@code CurrentActorPort}); recorded on the version
 */
public record PublishTemplateCommand(TenantId tenantId, String templateKey, ApproverRef actor) {
}
