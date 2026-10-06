package com.flowpass.approval.infrastructure.persistence;

import com.flowpass.approval.infrastructure.workflow.WorkflowRefRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** MyBatis-backed {@link WorkflowRefRepository}. */
@Repository
public class MybatisWorkflowRefRepository implements WorkflowRefRepository {

    private final WorkflowRefMapper mapper;

    public MybatisWorkflowRefRepository(WorkflowRefMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(String tenantId, String instanceId, String definitionKey, Integer definitionVersion,
                     String processInstanceId) {
        WorkflowRefRow row = new WorkflowRefRow();
        row.setTenantId(tenantId);
        row.setInstanceId(instanceId);
        row.setDefinitionKey(definitionKey);
        row.setDefinitionVersion(definitionVersion);
        row.setProcessInstanceId(processInstanceId);
        mapper.insert(row);
    }

    @Override
    public Optional<WorkflowRefRow> findByInstanceIdForUpdate(String tenantId, String instanceId) {
        return Optional.ofNullable(mapper.findByInstanceIdForUpdate(tenantId, instanceId));
    }

    @Override
    public Optional<WorkflowRefRow> findByInstanceId(String tenantId, String instanceId) {
        return Optional.ofNullable(mapper.findByInstanceId(tenantId, instanceId));
    }
}
