package com.flowpass.approval.domain.event;

import java.time.Instant;

/**
 * Marker for domain events. Events are named in the past tense (e.g. {@code ApprovalInstanceApproved})
 * and are emitted by aggregates for every state transition. They are consumed by the Outbox adapter and
 * published to listeners / webhooks after the local transaction commits.
 */
public interface DomainEvent {

    /** Instance / aggregate key this event belongs to. */
    String aggregateId();

    /** UTC instant the event occurred. */
    Instant occurredAt();
}
