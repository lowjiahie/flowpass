package com.flowpass.approval.domain.event;

import java.time.Instant;

/** Emitted when an approval instance reaches {@code REJECTED}. */
public record ApprovalInstanceRejected(String aggregateId, String tenantId, String actorId, Instant occurredAt)
        implements DomainEvent {
}
