package com.flowpass.approval.contract.dto;

import java.io.Serializable;
import java.time.Instant;

/**
 * Public read model for a webhook delivery row (admin/ops view).
 *
 * <p>Carries the destination, the attempt ledger ({@code attempt} + {@code status}) and the next retry
 * time so operators can inspect whether a delivery was delivered, is awaiting retry, or failed.</p>
 */
public record WebhookDeliveryDTO(
        String id, String eventId, String tenantId, String endpoint,
        int attempt, String status, Instant createdAt, Instant nextRetryAt) implements Serializable {

    private static final long serialVersionUID = 1L;
}
