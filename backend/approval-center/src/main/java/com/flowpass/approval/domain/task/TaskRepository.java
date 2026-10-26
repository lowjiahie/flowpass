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

    /** Persist a new assignee with optimistic versioning (a concurrent change is a {@code CONFLICT}). */
    void updateAssignee(ApprovalTask task);

    /**
     * Open tasks whose reminder time has come and that were not reminded yet — across tenants (a system worker's
     * view; each task is then processed within its own tenant), oldest reminder first.
     */
    List<DueTaskRef> findDueForReminder(java.time.Instant now, int limit);

    /** Open tasks past their deadline whose overdue handling has not happened yet — across tenants, oldest first. */
    List<DueTaskRef> findOverdue(java.time.Instant now, int limit);

    /** Atomically mark the task reminded; false when it was already reminded, finished, or not due (another worker won). */
    boolean claimReminder(TenantId tenantId, String taskId, java.time.Instant now);

    /** Atomically mark the task's overdue handling started; false when it was already, or the task is finished. */
    boolean claimOverdue(TenantId tenantId, String taskId, java.time.Instant now);

    /** True when the user already holds, or held, a task of this instance in any state. */
    boolean existsUserTask(TenantId tenantId, String instanceId, String userId);

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
