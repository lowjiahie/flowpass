package com.flowpass.approval.application.port;

import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.event.DomainEvent;

import java.util.List;

/**
 * Outbound port for the transactional outbox (charter 5 / 8).
 *
 * <p>Domain events are written to the {@code outbox_event} table in the same local transaction as the
 * aggregate change, then dispatched asynchronously after commit. The adapter only persists; the
 * worker that publishes to webhooks is added in a later milestone (M3).</p>
 */
public interface OutboxPublisherPort {

    /**
     * Persist the given events as {@code PENDING} outbox rows.
     *
     * @param tenantId    tenant scope
     * @param aggregateId aggregate the events belong to
     * @param aggregateType stable aggregate type name, e.g. {@code ApprovalInstance}
     * @param events      drained domain events (may be empty — handled as a no-op)
     */
    void append(TenantId tenantId, String aggregateId, String aggregateType, List<DomainEvent> events);
}
