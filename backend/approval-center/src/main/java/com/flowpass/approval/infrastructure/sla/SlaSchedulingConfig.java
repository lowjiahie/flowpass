package com.flowpass.approval.infrastructure.sla;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} for the deadline worker only when it is switched on. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flowpass.sla.scheduler-enabled", havingValue = "true")
public class SlaSchedulingConfig {
}
