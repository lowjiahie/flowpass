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
 */
public record ApprovalTaskDTO(
        String id,
        String instanceId,
        String name,
        String assigneeType,
        String assigneeId,
        String status,
        long version) implements Serializable {

    private static final long serialVersionUID = 1L;
}
