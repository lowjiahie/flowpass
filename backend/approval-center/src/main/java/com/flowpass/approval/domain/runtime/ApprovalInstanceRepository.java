package com.flowpass.approval.domain.runtime;

import com.flowpass.approval.domain.common.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for {@link ApprovalInstance}. Consumers depend on this interface only.
 *
 * <p>The interface lives in {@code domain}; its adapter ({@code infrastructure/persistence})
 * implements it. All methods are tenant-scoped — callers must pass the trusted tenant id and the
 * aggregate is loaded through the tenant boundary.</p>
 */
public interface ApprovalInstanceRepository {

    /** Load a single instance scoped by tenant + id. */
    Optional<ApprovalInstance> findById(TenantId tenantId, ApprovalId id);

    /** Persist the aggregate (insert or update) with optimistic versioning. */
    void save(ApprovalInstance instance);

    /**
     * Page instances for a tenant, ordered by id ascending (id-based cursor pagination).
     *
     * @param status   optional status filter (may be null)
     * @param cursorId optional last-seen id (exclusive lower bound, may be null for the first page)
     * @param limit    maximum page size
     */
    List<ApprovalInstance> findPage(TenantId tenantId, String status, String cursorId, int limit);
}
