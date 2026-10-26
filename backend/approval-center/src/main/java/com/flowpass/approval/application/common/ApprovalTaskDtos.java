package com.flowpass.approval.application.common;

import com.flowpass.approval.contract.dto.ApprovalTaskDTO;
import com.flowpass.approval.domain.task.ApprovalTask;

/** Maps a task to its public form. */
public final class ApprovalTaskDtos {

    private ApprovalTaskDtos() {
    }

    public static ApprovalTaskDTO of(ApprovalTask task) {
        return new ApprovalTaskDTO(
                task.id(),
                task.instanceId(),
                task.name(),
                task.assignee() == null ? null : task.assignee().type(),
                task.assignee() == null ? null : task.assignee().id(),
                task.status().name(),
                task.version(),
                task.dueAt() == null ? null : task.dueAt().toString());
    }
}
