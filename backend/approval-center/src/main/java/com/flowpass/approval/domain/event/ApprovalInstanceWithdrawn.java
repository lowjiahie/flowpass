package com.flowpass.approval.domain.event;

import java.time.Instant;

/** Emitted when an approval instance reaches {@code WITHDRAWN}. */
public record ApprovalInstanceWithdrawn(String aggregateId, String tenantId, String actorId, Instant occurredAt)
        implements DomainEvent {
}
