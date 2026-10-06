package com.flowpass.approval.application.port;

import com.flowpass.approval.domain.common.TenantId;

import java.util.List;

/**
 * Read/update side of the transactional outbox for the async dispatcher.
 *
 * <p>Pairs with {@link OutboxPublisherPort} (which only appends {@code PENDING} rows): the dispatcher
 * reads pending events, then marks them dispatched so they are picked up exactly once. The adapter in
 * {@code infrastructure} reads {@code publish_status = 'PENDING'} and flips it to {@code 'DISPATCHED'}.</p>
 */
public interface OutboxEventStore {

    /** The {@code PENDING} payload read by the dispatcher — never survives past the polling run. */
    record PendingOutboxEvent(String eventId, TenantId tenantId, String eventType, String payload) {
    }

    /**
     * Read up to {@code limit} pending outbox events, oldest first. The adapter decides the row
     * ordering (by {@code created_at}) so the dispatcher processes events in emission order.
     *
     * @return the pending events (may be empty)
     */
    List<PendingOutboxEvent> findPending(int limit);

    /** Mark a single event as dispatched so it is not picked up again. */
    void markDispatched(String eventId);

    /** Return the JSON payload of a persisted outbox event, regardless of its publish status. */
    String findPayload(String eventId);
}
