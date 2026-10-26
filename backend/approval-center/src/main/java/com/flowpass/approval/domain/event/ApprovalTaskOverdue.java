package com.flowpass.approval.domain.event;

import java.time.Instant;

/** Emitted once when a task passes its deadline. The aggregate id is the task id. */
public record ApprovalTaskOverdue(String aggregateId, String tenantId, String instanceId, String assigneeType, String assigneeId, Instant dueAt, Instant occurredAt)
        implements DomainEvent {
}
