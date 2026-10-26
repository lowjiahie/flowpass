package com.flowpass.approval.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/** Replaces the system clock with a {@link MutableClock} for integration tests that need to move time. */
@TestConfiguration
public class TestClockConfig {

    @Bean
    public MutableClock testClock() {
        return new MutableClock(Instant.parse("2026-06-01T09:00:00Z"));
    }

    @Bean
    @Primary
    public Clock clock(MutableClock testClock) {
        return testClock;
    }
}
