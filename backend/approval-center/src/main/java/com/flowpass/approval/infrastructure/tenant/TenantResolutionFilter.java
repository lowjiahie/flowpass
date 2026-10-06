package com.flowpass.approval.infrastructure.tenant;

import com.flowpass.approval.domain.common.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the request tenant once and publishes it to {@link TenantContextHolder}.
 *
 * <p>Replaces the per-endpoint header parsing the controllers used to repeat. The tenant comes from
 * {@code X-Tenant-Id}, defaulting to {@code default} for the no-Keycloak local profile. The context is
 * always cleared in a {@code finally} block so pooled threads cannot leak a tenant across requests.</p>
 *
 * <p>Active only under the {@code dev} profile — otherwise the tenant comes from the
 * verified JWT via {@link JwtTenantContextFilter} and this header path must not override it.</p>
 *
 * <p>Lives in {@code infrastructure}: the holder and the port adapter share this package, so the
 * {@code interfaces} layer depends only on {@link com.flowpass.approval.application.port.CurrentTenantPort}
 * and never on the thread-local machinery.</p>
 *
 * <p>TODO(security): this header is trusted as-is and exists only as the pre-identity adapter; once
 * Keycloak is wired for real it must derive from (or verify against) the authenticated token.</p>
 */
@Component
@Profile("dev")
public class TenantResolutionFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String DEFAULT_TENANT = "default";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantContextHolder.set(resolve(request));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private TenantId resolve(HttpServletRequest request) {
        String header = request.getHeader(TENANT_HEADER);
        String tenant = (header == null || header.isBlank()) ? DEFAULT_TENANT : header;
        return new TenantId(tenant);
    }
}
