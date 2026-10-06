package com.flowpass.approval.infrastructure.outbox;

import com.flowpass.approval.application.outbox.WebhookDispatchHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls due webhook deliveries on a fixed delay and hands them to the dispatcher.
 *
 * <p>Disabled by default (same {@code flowpass.outbox.scheduler-enabled} flag as
 * {@link OutboxDispatchScheduler}) so it cannot spawn background threads during the Testcontainers
 * lifecycle or block the JVM from exiting after tests. Enable in the runtime profile.</p>
 */
@Component
@ConditionalOnProperty(name = "flowpass.outbox.scheduler-enabled", havingValue = "true")
public class WebhookDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryScheduler.class);

    private final WebhookDispatchHandler dispatcher;
    private final int batchSize;

    public WebhookDeliveryScheduler(
            WebhookDispatchHandler dispatcher,
            @Value("${flowpass.webhook.batch-size:50}") int batchSize) {
        this.dispatcher = dispatcher;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${flowpass.webhook.poll-interval-ms:5000}",
            initialDelayString = "${flowpass.webhook.initial-delay-ms:1000}")
    public void pollDueDeliveries() {
        WebhookDispatchHandler.DispatchSummary summary = dispatcher.dispatch(batchSize);
        if (summary.delivered() > 0 || summary.failed() > 0) {
            log.debug("Webhook poll delivered {}, retried {}, failed {}",
                    summary.delivered(), summary.retried(), summary.failed());
        }
    }
}
