package com.flowpass.approval.application.port;

import com.flowpass.approval.domain.common.TenantId;

import java.time.Instant;

/**
 * A webhook delivery tracked by the outbox dispatcher and the webhook delivery worker.
 *
 * <p>Carries the resolved destination plus the outbox event it belongs to. The adapter writes it into
 * {@code webhook_delivery} with {@code attempt = 0} and {@code status = 'PENDING'}. {@code nextRetryAt} is
 * null until a failed attempt schedules a retry.</p>
 */
public record WebhookDelivery(String id, String eventId, TenantId tenantId, String endpoint, int attempt,
                              String status, Instant createdAt, Instant nextRetryAt) {
}
