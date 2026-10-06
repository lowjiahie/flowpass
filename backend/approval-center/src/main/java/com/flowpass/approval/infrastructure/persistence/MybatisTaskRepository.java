package com.flowpass.approval.infrastructure.persistence;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.AssigneeFilter;
import com.flowpass.approval.domain.task.TaskRepository;
import com.flowpass.approval.domain.task.TaskStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** MyBatis-backed {@link TaskRepository}. */
@Repository
public class MybatisTaskRepository implements TaskRepository {

    private final ApprovalTaskMapper mapper;

    public MybatisTaskRepository(ApprovalTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ApprovalTask> findById(TenantId tenantId, String taskId) {
        ApprovalTaskRow row = mapper.findById(tenantId.value(), taskId);
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public void save(ApprovalTask task) {
        mapper.insert(toRow(task));
    }

    @Override
    public void updateStatus(ApprovalTask task) {
        int updated = mapper.updateStatus(task.id(), task.status().name(), task.version());
        if (updated == 0) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "Concurrent modification on task " + task.id());
        }
    }

    @Override
    public List<ApprovalTask> findOpenByInstance(TenantId tenantId, String instanceId) {
        return mapper.findOpenByInstance(tenantId.value(), instanceId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<ApprovalTask> findPage(TenantId tenantId, String instanceId, TaskStatus status,
                                       AssigneeFilter assignee, String cursor, int limit) {
        String statusName = status == null ? null : status.name();
        String userId = assignee == null ? null : assignee.userId();
        List<String> roles = assignee == null ? List.of() : List.copyOf(assignee.roles());
        return mapper.findPage(tenantId.value(), instanceId, statusName, userId, roles, cursor, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    private ApprovalTask toDomain(ApprovalTaskRow row) {
        return ApprovalTask.hydrate(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getInstanceId(),
                row.getName(),
                row.getAssigneeType(),
                row.getAssigneeId(),
                TaskStatus.valueOf(row.getStatus()),
                row.getVersion(),
                row.getNodeKey(),
                row.getWorkflowTaskRef());
    }

    private ApprovalTaskRow toRow(ApprovalTask task) {
        ApprovalTaskRow row = new ApprovalTaskRow();
        row.setId(task.id());
        row.setTenantId(task.tenantId().value());
        row.setInstanceId(task.instanceId());
        row.setName(task.name());
        if (task.assignee() != null) {
            row.setAssigneeType(task.assignee().type());
            row.setAssigneeId(task.assignee().id());
        }
        row.setStatus(task.status().name());
        row.setVersion(task.version());
        row.setNodeKey(task.nodeKey());
        row.setWorkflowTaskRef(task.workflowTaskRef());
        return row;
    }
}
