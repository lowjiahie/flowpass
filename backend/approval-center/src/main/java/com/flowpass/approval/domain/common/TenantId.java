package com.flowpass.approval.domain.common;

import java.util.Objects;

/**
 * Tenant id value object — the trusted multi-tenant boundary.
 *
 * <p>Every business aggregate carries one; it is resolved and verified server-side, never taken
 * blindly from a client-submitted value. Used as a repository filtering key and in composite
 * unique keys.</p>
 */
public record TenantId(String value) {

    public TenantId {
        Objects.requireNonNull(value, "tenantId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }
}
