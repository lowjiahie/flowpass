package com.flowpass.approval.application.outbox;

import com.flowpass.approval.application.port.OutboxEventStore;
import com.flowpass.approval.application.port.WebhookDelivery;
import com.flowpass.approval.application.port.WebhookDeliveryStore;
import com.flowpass.approval.domain.integration.WebhookSubscription;
import com.flowpass.approval.domain.integration.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Outbox dispatch use case — the asynchronous worker that drains {@code PENDING} outbox events and
 * enqueues a webhook delivery per matched subscription.
 *
 * <p>For each pending event it resolves the enabled subscriptions for its tenant + event type, records a
 * {@code PENDING} delivery for each, then marks the event dispatched. Events without any subscription are
 * left {@code PENDING} so they are still delivered once a subscription appears. The whole run is one
 * transaction so the deliveries and the dispatched flag commit atomically.</p>
 *
 * <p>No business rules live here; the subscription resolution is behind a domain port. Actual HTTP sending
 * is a later milestone — this use case only enqueues delivery rows.</p>
 */
@Service
public class OutboxDispatchHandler {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchHandler.class);

    /** Result of one dispatch run, useful for observability and tests. */
    public record DispatchSummary(int eventsDrained, int deliveriesEnqueued) {
    }

    private final OutboxEventStore outboxEventStore;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryStore deliveryStore;

    public OutboxDispatchHandler(
            OutboxEventStore outboxEventStore,
            WebhookSubscriptionRepository subscriptionRepository,
            WebhookDeliveryStore deliveryStore) {
        this.outboxEventStore = outboxEventStore;
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryStore = deliveryStore;
    }

    /**
     * Process up to {@code limit} pending outbox events.
     *
     * @param limit maximum events to drain in this run
     * @return how many events were drained and how many deliveries were enqueued
     */
    @Transactional
    public DispatchSummary dispatch(int limit) {
        List<OutboxEventStore.PendingOutboxEvent> pending = outboxEventStore.findPending(limit);
        int drained = 0;
        int enqueued = 0;
        for (OutboxEventStore.PendingOutboxEvent event : pending) {
            // Only mark dispatched once at least one delivery was enqueued, so an event with no
            // subscription yet stays PENDING and is reaped once a subscription appears.
            int created = enqueueDeliveries(event);
            if (created > 0) {
                outboxEventStore.markDispatched(event.eventId());
                drained++;
            }
            enqueued += created;
        }
        log.debug("Dispatched {} outbox events draining {}, {} deliveries",
                pending.size(), drained, enqueued);
        return new DispatchSummary(drained, enqueued);
    }

    /** Create one {@code PENDING} delivery per enabled subscription; returns how many were enqueued. */
    private int enqueueDeliveries(OutboxEventStore.PendingOutboxEvent event) {
        List<WebhookSubscription> subscriptions = subscriptionRepository.findEnabled(event.tenantId(), event.eventType());
        if (subscriptions.isEmpty()) {
            // No destination yet — leave the event PENDING so it is reaped once a subscription appears.
            return 0;
        }
        Instant now = Instant.now();
        List<WebhookDelivery> deliveries = new ArrayList<>(subscriptions.size());
        for (WebhookSubscription subscription : subscriptions) {
            deliveries.add(new WebhookDelivery(
                    UUID.randomUUID().toString(),
                    event.eventId(),
                    event.tenantId(),
                    subscription.endpoint(),
                    0,
                    "PENDING",
                    now,
                    null));
        }
        deliveryStore.recordAll(deliveries);
        return deliveries.size();
    }
}
