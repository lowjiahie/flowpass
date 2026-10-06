package com.flowpass.approval.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.Money;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.runtime.ApprovalId;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.runtime.ApprovalStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter implementing the {@link ApprovalInstanceRepository} port on top of MyBatis.
 *
 * <p>Hides row/relational concerns from the domain: it converts between the persistence row and the
 * aggregate. Optimistic concurrency is enforced via the version check in the mapper SQL (an affected
 * row count of zero means concurrent modification).</p>
 */
@Repository
public class MybatisApprovalInstanceRepository implements ApprovalInstanceRepository {

    private final ApprovalInstanceMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisApprovalInstanceRepository(ApprovalInstanceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ApprovalInstance> findById(TenantId tenantId, ApprovalId id) {
        ApprovalInstanceRow row = mapper.findById(tenantId.value(), id.value());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public List<ApprovalInstance> findPage(TenantId tenantId, String status, String cursorId, int limit) {
        return mapper.findPage(tenantId.value(), status, cursorId, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void save(ApprovalInstance instance) {
        ApprovalInstanceRow row = toRow(instance);
        if (instance.version() == 0L) {
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException e) {
                // (tenant, application, business key) is unique: a second start for the same business object.
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "An approval already exists for business key " + instance.businessKey());
            }
            return;
        }
        int updated = mapper.updateStatus(instance.id().value(), instance.status().name(), instance.version());
        if (updated == 0) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "Concurrent modification detected");
        }
    }

    private ApprovalInstance toDomain(ApprovalInstanceRow row) {
        Money money = (row.getAmount() == null || row.getCurrency() == null)
                ? null
                : new Money(row.getAmount(), row.getCurrency());
        return ApprovalInstance.hydrate(
                new ApprovalId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getApplicationId(),
                row.getBusinessKey(),
                row.getTemplateKey(),
                new ApproverRef(row.getInitiatorType(), row.getInitiatorId()),
                money,
                row.getDefinitionVersion(),
                fromJson(row.getVariablesJson()),
                ApprovalStatus.valueOf(row.getStatus()),
                row.getVersion());
    }

    private ApprovalInstanceRow toRow(ApprovalInstance instance) {
        ApprovalInstanceRow row = new ApprovalInstanceRow();
        row.setId(instance.id().value());
        row.setTenantId(instance.tenantId().value());
        row.setApplicationId(instance.applicationId());
        row.setBusinessKey(instance.businessKey());
        row.setTemplateKey(instance.templateKey());
        row.setInitiatorType(instance.initiator().type());
        row.setInitiatorId(instance.initiator().id());
        row.setAmount(instance.money() == null ? null : instance.money().amount());
        row.setCurrency(instance.money() == null ? null : instance.money().currency());
        row.setDefinitionVersion(instance.definitionVersion());
        row.setStatus(instance.status().name());
        row.setVersion(instance.version());
        row.setVariablesJson(toJson(instance.variables()));
        return row;
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize instance variables snapshot", e);
        }
    }

    private String toJson(Map<String, Object> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize instance variables snapshot", e);
        }
    }
}
