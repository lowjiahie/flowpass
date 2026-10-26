package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.contract.dto.DelegationDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.delegation.Delegation;
import com.flowpass.approval.domain.delegation.DelegationRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Use cases for delegation: a person delegates <em>their own</em> new tasks to someone else for a bounded period.
 * Only a user can delegate, only their own delegations can be listed or revoked, and the periods of one person never
 * overlap. The delegation takes effect when a task opens (see {@link WorkflowTaskMaterializer}); tasks already open
 * stay with their assignee — use transfer for those.
 */
@Service
public class DelegationHandlers {

    private final DelegationRepository delegations;
    private final Clock clock;

    public DelegationHandlers(DelegationRepository delegations, Clock clock) {
        this.delegations = delegations;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_APPROVE + "')")
    @Transactional
    public DelegationDTO create(TenantId tenantId, ApproverRef actor, String delegateId, Instant startsAt, Instant endsAt) {
        requireUser(actor);
        Instant now = clock.instant();
        Delegation delegation;
        try {
            delegation = Delegation.create(tenantId, actor.id(), delegateId, startsAt, endsAt, now);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        boolean overlaps = delegations.findCurrentAndFuture(tenantId, actor.id(), now).stream()
                .anyMatch(existing -> existing.overlaps(delegation.startsAt(), delegation.endsAt()));
        if (overlaps) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED,
                    "The period overlaps another delegation of yours; revoke it first");
        }
        delegations.save(delegation);
        return toDto(delegation, now);
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_APPROVE + "')")
    @Transactional(readOnly = true)
    public List<DelegationDTO> mine(TenantId tenantId, ApproverRef actor) {
        requireUser(actor);
        Instant now = clock.instant();
        return delegations.findCurrentAndFuture(tenantId, actor.id(), now).stream().map(d -> toDto(d, now)).toList();
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_APPROVE + "')")
    @Transactional
    public void revoke(TenantId tenantId, ApproverRef actor, String delegationId) {
        requireUser(actor);
        // Somebody else's delegation looks exactly like a missing one.
        Delegation delegation = delegations.findById(tenantId, delegationId)
                .filter(d -> d.delegatorId().equals(actor.id()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.DELEGATION_NOT_FOUND,
                        "Delegation not found: " + delegationId));
        try {
            delegation.revoke(clock.instant());
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }
        delegations.markRevoked(delegation);
    }

    private static void requireUser(ApproverRef actor) {
        if (!"USER".equals(actor.type())) {
            throw new BusinessException(BusinessErrorCode.FORBIDDEN, "Only a user can delegate their tasks");
        }
    }

    private static DelegationDTO toDto(Delegation d, Instant now) {
        return new DelegationDTO(d.id(), d.delegatorId(), d.delegateId(), d.startsAt().toString(), d.endsAt().toString(),
                d.isActiveAt(now));
    }
}
