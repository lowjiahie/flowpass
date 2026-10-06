package com.flowpass.approval.application.port;

import com.flowpass.approval.domain.common.TenantId;

/**
 * Outbound port exposing the tenant of the current request.
 *
 * <p>Tenant identity is accessed only through ports (charter §6), so callers read it here instead of a
 * framework request object. The adapter resolves it from the inbound request (header today, the
 * authenticated token once the identity adapter is wired).</p>
 *
 * <p>Request-scoped: callers on non-request threads (outbox/webhook schedulers, consumers) must pass
 * {@link TenantId} explicitly — there is no ambient tenant off the request thread.</p>
 */
public interface CurrentTenantPort {

    /** The trusted tenant of the current request. */
    TenantId currentTenant();
}
