package com.flowpass.approval.domain.integration;

import com.flowpass.approval.domain.common.TenantId;

import java.util.List;

/**
 * Outbound port for resolving webhook subscriptions.
 *
 * <p>Lives in {@code domain} so the application dispatch use case can resolve destinations without
 * knowing how subscriptions are stored. The adapter in {@code infrastructure} reads only enabled
 * subscriptions for a tenant + event type.</p>
 */
public interface WebhookSubscriptionRepository {

    /** Resolve the {@code enabled} subscriptions for a tenant + event type, if any. */
    List<WebhookSubscription> findEnabled(TenantId tenantId, String eventType);
}
