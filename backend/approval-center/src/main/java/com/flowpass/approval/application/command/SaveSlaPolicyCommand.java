package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.TenantId;

/** Command to set the SLA policy of a template. {@code onOverdue} is the action name as sent by the client. */
public record SaveSlaPolicyCommand(TenantId tenantId, String templateKey, int dueAfterHours, Integer remindBeforeHours,
                                   String onOverdue, String escalateToUser) {
}
