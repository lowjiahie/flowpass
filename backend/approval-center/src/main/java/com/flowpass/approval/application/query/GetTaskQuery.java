package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;

/** Query object for fetching a single approval task. */
public record GetTaskQuery(TenantId tenantId, String taskId) {
}
