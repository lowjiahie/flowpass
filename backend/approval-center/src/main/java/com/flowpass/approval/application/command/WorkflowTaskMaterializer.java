package com.flowpass.approval.application.command;

import com.flowpass.approval.application.port.WorkflowTask;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns the user tasks the process engine opened into business {@link ApprovalTask}s.
 *
 * <p>The engine decides <em>which</em> tasks exist; this is the single place where each one becomes a
 * platform task (own id, assignee, node key, opaque engine reference). Used by every use case that can
 * advance a run — start and approve today, any future step-opening action tomorrow — so the two views
 * cannot drift. An engine task without an assignee (e.g. the manual-review parking task) has no
 * accountable approver, so no business task is created for it.</p>
 */
@Component
public class WorkflowTaskMaterializer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskMaterializer.class);

    private final TaskRepository taskRepository;

    public WorkflowTaskMaterializer(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /** Persist one {@code PENDING} task per assigned engine task; returns the tasks created. */
    public List<ApprovalTask> materialize(TenantId tenantId, String instanceId, List<WorkflowTask> workflowTasks) {
        return workflowTasks.stream()
                .filter(workflowTask -> {
                    if (!workflowTask.assigned()) {
                        log.warn("Instance {} (tenant {}): engine task at node {} has no assignee — left for manual handling",
                                instanceId, tenantId.value(), workflowTask.nodeKey());
                    }
                    return workflowTask.assigned();
                })
                .map(workflowTask -> {
                    ApprovalTask task = ApprovalTask.start(
                            tenantId,
                            instanceId,
                            workflowTask.name(),
                            new ApproverRef(workflowTask.assigneeType(), workflowTask.assigneeId()),
                            workflowTask.nodeKey(),
                            workflowTask.ref());
                    taskRepository.save(task);
                    return task;
                })
                .toList();
    }
}
