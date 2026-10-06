package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.common.TenantId;

/** Read query for pageable audit entries (admin timeline view). */
public record ListAuditRecordsQuery(TenantId tenantId, String instanceId, AuditAction action, String cursor, int limit) {
}
