package com.flowpass.approval.infrastructure.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the header-trusting {@code dev} identity is activated in a production
 * environment ({@code flowpass.environment=production}); otherwise logs a loud warning.
 */
@Component
@Profile("dev")
public class DevProfileGuard {

    private static final Logger log = LoggerFactory.getLogger(DevProfileGuard.class);

    private final String environment;

    public DevProfileGuard(@Value("${flowpass.environment:local}") String environment) {
        this.environment = environment;
    }

    @PostConstruct
    void check() {
        if ("production".equalsIgnoreCase(environment)) {
            throw new IllegalStateException(
                    "The dev profile trusts X-Actor-* headers and must not run with flowpass.environment=production");
        }
        log.warn("Profile dev active: request identity is taken from X-Actor-*/X-Tenant-Id headers. "
                + "NEVER use outside local development.");
    }
}
