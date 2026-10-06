package com.flowpass.approval.application.query;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.contract.dto.ApprovalTaskDTO;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read use case: fetch a single approval task, tenant-scoped.
 *
 * <p>Focused on one responsibility — load the aggregate through the port and map it to the public
 * contract DTO. No business rules here; those stay in the aggregate. The read path is not a place
 * to put authorization: authorization is enforced at the write handlers / entry point.</p>
 */
@Service
public class GetTaskHandler {

    private final TaskRepository taskRepository;

    public GetTaskHandler(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_READ + "')")
    @Transactional(readOnly = true)
    public ApprovalTaskDTO handle(GetTaskQuery query) {
        ApprovalTask task = taskRepository
                .findById(query.tenantId(), query.taskId())
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.TASK_NOT_FOUND,
                        "Approval task not found: " + query.taskId()));

        return new ApprovalTaskDTO(
                task.id(),
                task.instanceId(),
                task.name(),
                task.assignee() == null ? null : task.assignee().type(),
                task.assignee() == null ? null : task.assignee().id(),
                task.status().name(),
                task.version());
    }
}
