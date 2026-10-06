package com.flowpass.approval.application.port;

import com.flowpass.approval.domain.common.TenantId;

import java.time.Instant;
import java.util.List;

/**
 * Read/write side of the webhook delivery ledger for the async dispatcher.
 *
 * <p>The {@link com.flowpass.approval.application.outbox.OutboxDispatchHandler} resolves one or more enabled
 * {@link com.flowpass.approval.domain.integration.WebhookSubscription}s for an outbox event and records a delivery
 * row per subscription; the {@code WebhookDispatchHandler} then reads due rows and updates them after each
 * attempt. The adapter in {@code infrastructure} maps to {@code webhook_delivery}.</p>
 */
public interface WebhookDeliveryStore {

    /** Bulk-insert pending deliveries (one per matched subscription). */
    void recordAll(List<WebhookDelivery> deliveries);

    /**
     * Read up to {@code limit} deliveries that are due for a send — {@code PENDING} with no future retry
     * time, oldest first. Rows already awaiting a retry ({@code next_retry_at} in the future) are skipped.
     */
    List<WebhookDelivery> findDue(int limit);

    /** Update the delivery ledger after one attempt: attempt count, resulting status and the next retry time. */
    void updateStatus(String id, int attempt, String status, Instant nextRetryAt);

    /**
     * Admin read: page deliveries for a tenant, optionally filtered by status, ordered by id ascending
     * with a cursor run-off (exclusive lower bound). Returns at most {@code limit} rows.
     */
    List<WebhookDelivery> findPage(TenantId tenantId, String status, String cursor, int limit);
}
