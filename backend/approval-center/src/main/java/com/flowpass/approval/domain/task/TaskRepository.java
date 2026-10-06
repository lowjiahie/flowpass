package com.flowpass.approval.domain.task;

import com.flowpass.approval.domain.common.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting tasks in the {@code task} bounded context.
 */
public interface TaskRepository {

    /** Find a task by tenant + id. */
    Optional<ApprovalTask> findById(TenantId tenantId, String taskId);

    /** Persist a new task (insert for first-round tasks). */
    void save(ApprovalTask task);

    /**
     * Persist a state transition with optimistic versioning. Throws a conflict when the version check
     * fails (concurrent modification).
     */
    void updateStatus(ApprovalTask task);

    /** All tasks of an instance that can still be acted on ({@code PENDING} or {@code CLAIMED}). */
    List<ApprovalTask> findOpenByInstance(TenantId tenantId, String instanceId);

    /**
     * Page tasks for a tenant, ordered by id ascending. Filtering by instance, status and assignee is
     * optional (null = all). {@code cursor} is the last-seen task id (exclusive lower bound, null = first
     * page). With an {@link AssigneeFilter} only tasks assigned to that user, or to a role they hold, match.
     */
    List<ApprovalTask> findPage(TenantId tenantId, String instanceId, TaskStatus status, AssigneeFilter assignee,
                                String cursor, int limit);
}
