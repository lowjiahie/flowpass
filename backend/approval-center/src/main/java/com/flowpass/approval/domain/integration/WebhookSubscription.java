package com.flowpass.approval.domain.integration;

import com.flowpass.approval.domain.common.TenantId;

import java.util.Objects;

/**
 * Registry entry telling the outbox dispatcher where to deliver a given event type.
 *
 * <p>One subscription maps a {@code (tenant, eventType)} pair to a single webhook {@code endpoint}.
 * It lives in the integration bounded context and carries no outbox mechanics — the dispatcher in the
 * application layer resolves subscriptions by tenant + event type and enqueues a delivery row.</p>
 */
public record WebhookSubscription(String id, TenantId tenantId, String eventType, String endpoint, boolean enabled) {

    public WebhookSubscription {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (id.isBlank() || eventType.isBlank() || endpoint.isBlank()) {
            throw new IllegalArgumentException("id, eventType and endpoint must not be blank");
        }
    }
}
