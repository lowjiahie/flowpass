package com.flowpass.approval.domain.delegation;

import com.flowpass.approval.domain.common.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port for delegations (tenant-scoped). */
public interface DelegationRepository {

    void save(Delegation delegation);

    /** Persist a revocation. */
    void markRevoked(Delegation delegation);

    Optional<Delegation> findById(TenantId tenantId, String id);

    /** The delegation in force for the delegator at {@code at}, if any (periods of one delegator never overlap). */
    Optional<Delegation> findActive(TenantId tenantId, String delegatorId, Instant at);

    /** The delegator's delegations that are not revoked and have not ended yet, soonest first. */
    List<Delegation> findCurrentAndFuture(TenantId tenantId, String delegatorId, Instant now);
}
