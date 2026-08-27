package com.flowpass.approval.domain.event;

import java.time.Instant;

/** Emitted when an approval instance is started. */
public record ApprovalInstanceCreated(String aggregateId, String tenantId, String businessKey, Instant occurredAt)
        implements DomainEvent {
}
