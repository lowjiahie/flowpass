package com.flowpass.approval.domain.event;

import java.time.Instant;

/** Emitted when a task is approaching its deadline (a reminder for the assignee). The aggregate id is the task id. */
public record ApprovalTaskReminded(String aggregateId, String tenantId, String instanceId, String assigneeType, String assigneeId, Instant dueAt, Instant occurredAt)
        implements DomainEvent {
}
