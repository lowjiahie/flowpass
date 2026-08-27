/**
 * Integration bounded context: client applications, webhook subscriptions and delivery records.
 *
 * <p>Aggregates: {@code ClientApplication}, {@code WebhookSubscription}, {@code Delivery}. Webhooks are
 * driven by the Outbox, HMAC-signed and idempotent by event id. Empty in the M0 skeleton.</p>
 */
package com.flowpass.approval.domain.integration;
