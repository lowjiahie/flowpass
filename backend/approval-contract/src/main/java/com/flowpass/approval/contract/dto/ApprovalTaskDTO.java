package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * Public read model of an approval task.
 *
 * <p>Never exposes domain entities or Flowable internals. This is the single shape returned to
 * external systems and consumed by the BFF.</p>
 *
 * @param id           platform task id
 * @param instanceId   owning approval instance id
 * @param name         human-readable work item name
 * @param assigneeType {@code USER} or {@code SYSTEM}
 * @param assigneeId   acting assignee id (null when unassigned)
 * @param status       current public task status
 * @param version      optimistic-lock version
 * @param dueAt        when the task is due (UTC, ISO-8601), or null when its template has no SLA
 */
public record ApprovalTaskDTO(
        String id,
        String instanceId,
        String name,
        String assigneeType,
        String assigneeId,
        String status,
        long version,
        String dueAt) implements Serializable {

    /** A task without a deadline. */
    public ApprovalTaskDTO(String id, String instanceId, String name, String assigneeType, String assigneeId,
                           String status, long version) {
        this(id, instanceId, name, assigneeType, assigneeId, status, version, null);
    }

    private static final long serialVersionUID = 1L;
}
