package com.flowpass.approval.application.query;

import com.flowpass.approval.contract.dto.ApprovalTaskDTO;
import com.flowpass.approval.domain.directory.OrgDirectory;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.AssigneeFilter;
import com.flowpass.approval.domain.task.TaskRepository;
import com.flowpass.approval.domain.task.TaskStatus;
import com.flowpass.approval.application.common.ApprovalTaskDtos;
import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read use case: page approval tasks for a tenant using id-based cursor pagination.
 *
 * <p>Loads the aggregates through the port and maps them to the public DTO. Ordering is by id ascending;
 * the caller passes the last seen id as {@code cursor} to get the next page. No business rules live
 * here.</p>
 */
@Service
public class ListTasksHandler {

    private final TaskRepository taskRepository;
    private final OrgDirectory orgDirectory;

    public ListTasksHandler(TaskRepository taskRepository, OrgDirectory orgDirectory) {
        this.taskRepository = taskRepository;
        this.orgDirectory = orgDirectory;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_READ + "')")
    @Transactional(readOnly = true)
    public List<ApprovalTaskDTO> handle(ListTasksQuery query) {
        TaskStatus status = query.status() == null ? null : TaskStatus.valueOf(query.status());
        AssigneeFilter assignee = query.assignedTo() == null
                ? null
                : new AssigneeFilter(query.assignedTo().id(),
                        orgDirectory.rolesOf(query.tenantId(), query.assignedTo().id()));
        return taskRepository.findPage(query.tenantId(), query.instanceId(), status, assignee,
                        query.cursor(), query.limit())
                .stream()
                .map(this::toDto)
                .toList();
    }

    private ApprovalTaskDTO toDto(ApprovalTask task) {
        return ApprovalTaskDtos.of(task);
    }
}
