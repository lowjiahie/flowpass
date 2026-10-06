package com.flowpass.approval.infrastructure.tenant;

import com.flowpass.approval.domain.common.TenantId;

/**
 * Request-scoped holder for the resolved tenant, backed by a {@link ThreadLocal}.
 *
 * <p>Populated and cleared by {@link TenantResolutionFilter} around each request. The value must be
 * removed in a {@code finally} block: servlet threads are pooled, and a leaked value would attribute a
 * later request to the wrong tenant.</p>
 */
final class TenantContextHolder {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    static void set(TenantId tenantId) {
        CURRENT.set(tenantId);
    }

    static TenantId current() {
        return CURRENT.get();
    }

    static void clear() {
        CURRENT.remove();
    }
}
