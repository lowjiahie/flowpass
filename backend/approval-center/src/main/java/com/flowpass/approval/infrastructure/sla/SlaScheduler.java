package com.flowpass.approval.infrastructure.sla;

import com.flowpass.approval.application.sla.ProcessSlaHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Runs the deadline worker on a fixed delay. Off by default (so no background thread starts in the test lifecycle);
 * switch it on with {@code flowpass.sla.scheduler-enabled=true}. Safe to run on several nodes at once: every
 * reminder and escalation is claimed with a conditional update, so it happens exactly once.
 */
@Component
@ConditionalOnProperty(name = "flowpass.sla.scheduler-enabled", havingValue = "true")
public class SlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaScheduler.class);

    private final ProcessSlaHandler handler;
    private final Clock clock;
    private final int batchSize;

    public SlaScheduler(ProcessSlaHandler handler, Clock clock,
                        @Value("${flowpass.sla.batch-size:100}") int batchSize) {
        this.handler = handler;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${flowpass.sla.poll-interval-ms:60000}",
            initialDelayString = "${flowpass.sla.initial-delay-ms:5000}")
    public void tick() {
        ProcessSlaHandler.Summary summary = handler.run(clock.instant(), batchSize);
        if (summary.reminded() + summary.overdue() + summary.failed() > 0) {
            log.info("SLA run: {} reminded, {} overdue ({} escalated), {} failed",
                    summary.reminded(), summary.overdue(), summary.escalated(), summary.failed());
        }
    }
}
