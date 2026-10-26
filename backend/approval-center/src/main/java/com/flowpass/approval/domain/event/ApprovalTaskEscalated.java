package com.flowpass.approval.domain.event;

import java.time.Instant;

/** Emitted when an overdue task is handed to somebody else. The aggregate id is the task id. */
public record ApprovalTaskEscalated(String aggregateId, String tenantId, String instanceId, String fromAssigneeType, String fromAssigneeId, String toUserId, Instant occurredAt)
        implements DomainEvent {
}
