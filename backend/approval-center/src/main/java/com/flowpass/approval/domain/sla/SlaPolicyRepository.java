package com.flowpass.approval.domain.sla;

import com.flowpass.approval.domain.common.TenantId;

import java.util.Optional;

/** Outbound port for the per-template SLA policy (tenant-scoped, at most one per template). */
public interface SlaPolicyRepository {

    Optional<SlaPolicy> find(TenantId tenantId, String templateKey);

    /** Insert or replace the policy of the template. */
    void save(SlaPolicy policy);

    /** Remove the policy; returns false when there was none. */
    boolean delete(TenantId tenantId, String templateKey);
}
