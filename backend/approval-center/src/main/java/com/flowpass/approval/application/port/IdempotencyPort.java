package com.flowpass.approval.application.port;

import com.flowpass.approval.domain.common.TenantId;

/**
 * Outbound port for write-request idempotency (charter 8).
 *
 * <p>A public write API carries an {@code Idempotency-Key}. The adapter stores a tenant-scoped
 * record; a conflicting {@code (tenant, application, key)} signals a repeated request. The key is
 * claimed inside the same local transaction as the business write, so a failed write rolls the
 * claim back and the caller may safely retry.</p>
 */
public interface IdempotencyPort {

    /** Claim a request key. Throws a conflict business error when the key was already claimed. */
    void claim(TenantId tenantId, String applicationId, String idemKey, String responseRef);
}
