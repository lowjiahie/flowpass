package com.flowpass.approval.domain.task;

import com.flowpass.approval.domain.common.TenantId;

/** A task a deadline worker should look at: just enough to process it later within its own tenant scope. */
public record DueTaskRef(TenantId tenantId, String taskId) {
}
