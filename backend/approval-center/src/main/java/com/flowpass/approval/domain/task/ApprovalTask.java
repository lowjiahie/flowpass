package com.flowpass.approval.domain.task;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root in the {@code task} bounded context.
 *
 * <p>Represents a single work item assigned to an approver. Creation happens at start time (first round
 * resolvers), afterwards an operator claims / approves / rejects it. A task is always tied to exactly
 * one approval instance. State changes carry an optimistic {@code version} so concurrent operators
 * cannot double-complete it (M2 will add the transition methods).</p>
 */
public final class ApprovalTask {

    private final String id;
    private final TenantId tenantId;
    private final String instanceId;
    private final String name;
    private final ApproverRef assignee;
    private final String nodeKey;
    private final String workflowTaskRef;
    private TaskStatus status;
    private long version;

    private ApprovalTask(
            String id,
            TenantId tenantId,
            String instanceId,
            String name,
            ApproverRef assignee,
            TaskStatus status,
            long version,
            String nodeKey,
            String workflowTaskRef) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.instanceId = Objects.requireNonNull(instanceId);
        this.name = Objects.requireNonNull(name);
        this.assignee = assignee;
        this.nodeKey = nodeKey;
        this.workflowTaskRef = workflowTaskRef;
        this.status = Objects.requireNonNull(status);
        this.version = version;
    }

    /**
     * Create the business task for one workflow step.
     *
     * @param nodeKey         process node (BPMN element id) the task belongs to
     * @param workflowTaskRef opaque reference to the engine step; completing the task advances that step
     */
    public static ApprovalTask start(
            TenantId tenantId,
            String instanceId,
            String name,
            ApproverRef assignee,
            String nodeKey,
            String workflowTaskRef) {
        return new ApprovalTask(
                "task_" + UUID.randomUUID(),
                tenantId,
                instanceId,
                name,
                Objects.requireNonNull(assignee, "assignee is required at start"),
                TaskStatus.PENDING,
                0L,
                Objects.requireNonNull(nodeKey, "nodeKey is required"),
                Objects.requireNonNull(workflowTaskRef, "workflowTaskRef is required"));
    }

    /** Rebuild an aggregate from a persistence row. */
    public static ApprovalTask hydrate(
            String id,
            TenantId tenantId,
            String instanceId,
            String name,
            String assigneeType,
            String assigneeId,
            TaskStatus status,
            long version,
            String nodeKey,
            String workflowTaskRef) {
        ApproverRef assignee = (assigneeType == null || assigneeId == null)
                ? null
                : new ApproverRef(assigneeType, assigneeId);
        return new ApprovalTask(id, tenantId, instanceId, name, assignee, status, version, nodeKey, workflowTaskRef);
    }

    /** Claim a pending task for the operator ({@code PENDING → CLAIMED}). */
    public void claim() {
        if (status != TaskStatus.PENDING) {
            throw new IllegalStateException("Cannot claim a task in " + status);
        }
        this.status = TaskStatus.CLAIMED;
        this.version++;
    }

    /**
     * Approve the task ({@code PENDING} or {@code CLAIMED → APPROVED}). A terminal task cannot be
     * re-approved (state and version are owned by this aggregate).
     */
    public void approve() {
        if (status != TaskStatus.PENDING && status != TaskStatus.CLAIMED) {
            throw new IllegalStateException("Cannot approve a task in " + status);
        }
        this.status = TaskStatus.APPROVED;
        this.version++;
    }

    /**
     * Reject the task ({@code PENDING} or {@code CLAIMED → REJECTED}). A terminal task cannot be
     * re-rejected (state and version are owned by this aggregate).
     */
    public void reject() {
        if (status != TaskStatus.PENDING && status != TaskStatus.CLAIMED) {
            throw new IllegalStateException("Cannot reject a task in " + status);
        }
        this.status = TaskStatus.REJECTED;
        this.version++;
    }

    /**
     * Withdraw the task ({@code PENDING} or {@code CLAIMED → CANCELLED}). A terminal task cannot be
     * withdrawn (state and version are owned by this aggregate).
     */
    public void withdraw() {
        if (status != TaskStatus.PENDING && status != TaskStatus.CLAIMED) {
            throw new IllegalStateException("Cannot withdraw a task in " + status);
        }
        this.status = TaskStatus.CANCELLED;
        this.version++;
    }

    public String id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String instanceId() {
        return instanceId;
    }

    public String name() {
        return name;
    }

    public ApproverRef assignee() {
        return assignee;
    }

    /** Process node the task belongs to (null for tasks that predate the workflow link). */
    public String nodeKey() {
        return nodeKey;
    }

    /** Opaque reference to the engine step this task completes (null for tasks that predate the link). */
    public String workflowTaskRef() {
        return workflowTaskRef;
    }

    public TaskStatus status() {
        return status;
    }

    public long version() {
        return version;
    }
}
