package com.flowpass.approval.application.outbox;

import com.flowpass.approval.application.port.OutboxEventStore;
import com.flowpass.approval.application.port.WebhookDelivererPort;
import com.flowpass.approval.application.port.WebhookDelivery;
import com.flowpass.approval.application.port.WebhookDeliveryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Webhook delivery use case — the asynchronous worker that drains due {@code PENDING} deliveries and
 * sends them over HTTP via {@link WebhookDelivererPort}.
 *
 * <p>For each due delivery it resolves the originating outbox payload, sends it to the delivery endpoint,
 * then updates the ledger: a 2xx marks it {@code DELIVERED}; a transport failure or non-2xx increments
 * the attempt and either schedules a retry ({@code PENDING} with a future {@code next_retry_at}) or marks
 * the delivery {@code FAILED} once {@code maxAttempts} is reached.</p>
 *
 * <p>Deliberately <b>not</b> transactional: the HTTP call must not hold a database transaction open for
 * the duration of a network round-trip. Each ledger update is its own commit, and the find-then-update
 * split is idempotent under the single-scheduler deployment this project targets.</p>
 */
@Service
public class WebhookDispatchHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchHandler.class);

    private static final String PENDING = "PENDING";
    private static final String DELIVERED = "DELIVERED";
    private static final String FAILED = "FAILED";

    /** Result of one dispatch run, useful for observability and tests. */
    public record DispatchSummary(int delivered, int retried, int failed) {
    }

    private final WebhookDeliveryStore deliveryStore;
    private final OutboxEventStore outboxEventStore;
    private final WebhookDelivererPort deliverer;
    private final int maxAttempts;
    private final long retryDelayMs;

    public WebhookDispatchHandler(
            WebhookDeliveryStore deliveryStore,
            OutboxEventStore outboxEventStore,
            WebhookDelivererPort deliverer,
            @Value("${flowpass.webhook.max-attempts:3}") int maxAttempts,
            @Value("${flowpass.webhook.retry-delay-ms:30000}") long retryDelayMs) {
        this.deliveryStore = deliveryStore;
        this.outboxEventStore = outboxEventStore;
        this.deliverer = deliverer;
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;
    }

    /**
     * Send up to {@code limit} due deliveries.
     *
     * @param limit maximum deliveries to process in this run
     * @return how many were delivered, scheduled for retry, and failed permanently
     */
    public DispatchSummary dispatch(int limit) {
        List<WebhookDelivery> due = deliveryStore.findDue(limit);
        int delivered = 0;
        int retried = 0;
        int failed = 0;
        for (WebhookDelivery delivery : due) {
            String payload = outboxEventStore.findPayload(delivery.eventId());
            try {
                deliverer.deliver(delivery, payload);
                deliveryStore.updateStatus(delivery.id(), delivery.attempt(), DELIVERED, null);
                delivered++;
            } catch (RuntimeException ex) {
                int nextAttempt = delivery.attempt() + 1;
                if (nextAttempt >= maxAttempts) {
                    deliveryStore.updateStatus(delivery.id(), nextAttempt, FAILED, null);
                    failed++;
                } else {
                    Instant nextRetry = Instant.now().plus(retryDelayMs, ChronoUnit.MILLIS);
                    deliveryStore.updateStatus(delivery.id(), nextAttempt, PENDING, nextRetry);
                    retried++;
                }
                log.warn("Webhook delivery {} to {} failed on attempt {}: {}",
                        delivery.id(), delivery.endpoint(), nextAttempt, ex.getMessage());
            }
        }
        log.debug("Webhook dispatch processed {} due deliveries -> {} delivered, {} retried, {} failed",
                due.size(), delivered, retried, failed);
        return new DispatchSummary(delivered, retried, failed);
    }
}
