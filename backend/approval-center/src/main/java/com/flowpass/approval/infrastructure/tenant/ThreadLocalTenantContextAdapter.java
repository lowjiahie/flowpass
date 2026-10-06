package com.flowpass.approval.infrastructure.tenant;

import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.domain.common.TenantId;
import org.springframework.stereotype.Component;

/**
 * Adapter binding {@link CurrentTenantPort} to the request-scoped {@link TenantContextHolder}.
 *
 * <p>Fails fast when read off the request thread — a scheduler or consumer has no ambient tenant and
 * must pass {@link TenantId} explicitly, rather than silently acting on the wrong tenant.</p>
 */
@Component
public class ThreadLocalTenantContextAdapter implements CurrentTenantPort {

    @Override
    public TenantId currentTenant() {
        TenantId tenantId = TenantContextHolder.current();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant in the current context; CurrentTenantPort is request-scoped — pass TenantId "
                            + "explicitly off the request thread");
        }
        return tenantId;
    }
}
