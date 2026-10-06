package com.flowpass.approval.domain.audit;

import com.flowpass.approval.domain.common.TenantId;

import java.util.List;

/**
 * Persistence port for the {@link AuditRecord} aggregate.
 *
 * <p>Audit is append-only: the only operation is {@link #append}. There is no update or delete — a
 * recorded fact must never be altered. Implementations must scope writes by the record's tenant id and
 * reject attempts to re-use a primary key.</p>
 */
public interface AuditRecordRepository {

    /** Persist a new, immutable audit entry. No-op semantics are not allowed: it must be durable. */
    void append(AuditRecord record);

    /**
     * Page audit entries for a tenant, optionally filtered by instance id (timeline) and action.
     * Id-ascending cursor pagination, consistent with the other read paths.
     */
    List<AuditRecord> findPage(TenantId tenantId, String instanceId, AuditAction action, String cursor, int limit);
}
