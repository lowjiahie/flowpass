package com.flowpass.approval.infrastructure.persistence;

import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.sla.OverdueAction;
import com.flowpass.approval.domain.sla.SlaPolicy;
import com.flowpass.approval.domain.sla.SlaPolicyRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** MyBatis-backed {@link SlaPolicyRepository}. */
@Repository
public class MybatisSlaPolicyRepository implements SlaPolicyRepository {

    private final SlaPolicyMapper mapper;

    public MybatisSlaPolicyRepository(SlaPolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SlaPolicy> find(TenantId tenantId, String templateKey) {
        SlaPolicyRow row = mapper.find(tenantId.value(), templateKey);
        return row == null ? Optional.empty() : Optional.of(SlaPolicy.of(
                new TenantId(row.getTenantId()), row.getTemplateKey(), row.getDueAfterHours(),
                row.getRemindBeforeHours(), OverdueAction.valueOf(row.getOnOverdue()), row.getEscalateToUser()));
    }

    @Override
    public void save(SlaPolicy policy) {
        SlaPolicyRow row = new SlaPolicyRow();
        row.setTenantId(policy.tenantId().value());
        row.setTemplateKey(policy.templateKey());
        row.setDueAfterHours(policy.dueAfterHours());
        row.setRemindBeforeHours(policy.remindBeforeHours());
        row.setOnOverdue(policy.onOverdue().name());
        row.setEscalateToUser(policy.escalateToUser());
        mapper.upsert(row);
    }

    @Override
    public boolean delete(TenantId tenantId, String templateKey) {
        return mapper.delete(tenantId.value(), templateKey) > 0;
    }
}
