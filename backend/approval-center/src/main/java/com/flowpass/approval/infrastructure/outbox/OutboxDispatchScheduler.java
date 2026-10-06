package com.flowpass.approval.infrastructure.outbox;

import com.flowpass.approval.application.outbox.OutboxDispatchHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@code PENDING} outbox events on a fixed delay and hands them to the dispatcher.
 *
 * <p>Disabled by default so it cannot spawn background threads during the Testcontainers lifecycle or
 * block the JVM from exiting after tests. Enable by setting {@code flowpass.outbox.scheduler-enabled=true}
 * in the runtime profile.</p>
 */
@Component
@ConditionalOnProperty(name = "flowpass.outbox.scheduler-enabled", havingValue = "true")
public class OutboxDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchScheduler.class);

    private final OutboxDispatchHandler dispatcher;
    private final int batchSize;

    public OutboxDispatchScheduler(
            OutboxDispatchHandler dispatcher,
            @Value("${flowpass.outbox.batch-size:50}") int batchSize) {
        this.dispatcher = dispatcher;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${flowpass.outbox.poll-interval-ms:5000}",
            initialDelayString = "${flowpass.outbox.initial-delay-ms:1000}")
    public void pollPendingOutbox() {
        OutboxDispatchHandler.DispatchSummary summary = dispatcher.dispatch(batchSize);
        if (summary.eventsDrained() > 0) {
            log.debug("Outbox poll drained {} events, {} deliveries", summary.eventsDrained(), summary.deliveriesEnqueued());
        }
    }
}
