package com.flowpass.approval.infrastructure.persistence;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.delegation.Delegation;
import com.flowpass.approval.domain.delegation.DelegationRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** MyBatis-backed {@link DelegationRepository}. */
@Repository
public class MybatisDelegationRepository implements DelegationRepository {

    private final DelegationMapper mapper;

    public MybatisDelegationRepository(DelegationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(Delegation delegation) {
        DelegationRow row = new DelegationRow();
        row.setId(delegation.id());
        row.setTenantId(delegation.tenantId().value());
        row.setDelegatorId(delegation.delegatorId());
        row.setDelegateId(delegation.delegateId());
        row.setStartsAt(delegation.startsAt());
        row.setEndsAt(delegation.endsAt());
        mapper.insert(row);
    }

    @Override
    public void markRevoked(Delegation delegation) {
        if (mapper.revoke(delegation.tenantId().value(), delegation.id(), delegation.revokedAt()) == 0) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "The delegation was already revoked");
        }
    }

    @Override
    public Optional<Delegation> findById(TenantId tenantId, String id) {
        return Optional.ofNullable(mapper.findById(tenantId.value(), id)).map(this::toDomain);
    }

    @Override
    public Optional<Delegation> findActive(TenantId tenantId, String delegatorId, Instant at) {
        return Optional.ofNullable(mapper.findActive(tenantId.value(), delegatorId, at)).map(this::toDomain);
    }

    @Override
    public List<Delegation> findCurrentAndFuture(TenantId tenantId, String delegatorId, Instant now) {
        return mapper.findCurrentAndFuture(tenantId.value(), delegatorId, now).stream().map(this::toDomain).toList();
    }

    private Delegation toDomain(DelegationRow row) {
        return Delegation.hydrate(row.getId(), new TenantId(row.getTenantId()), row.getDelegatorId(),
                row.getDelegateId(), row.getStartsAt(), row.getEndsAt(), row.getRevokedAt());
    }
}
