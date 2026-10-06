package com.flowpass.approval.infrastructure.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the {@code @Scheduled} infrastructure only when the outbox poller is switched on.
 *
 * <p>Kept behind the same {@code flowpass.outbox.scheduler-enabled} flag as {@link OutboxDispatchScheduler}
 * so the scheduling machinery is never started in the default/test lifecycle — it would otherwise spawn
 * background threads that linger when the Spring context or Testcontainers shuts down.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flowpass.outbox.scheduler-enabled", havingValue = "true")
public class OutboxSchedulingConfig {
}
