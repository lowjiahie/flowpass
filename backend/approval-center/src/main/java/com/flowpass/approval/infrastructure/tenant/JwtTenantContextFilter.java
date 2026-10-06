package com.flowpass.approval.infrastructure.tenant;

import com.flowpass.approval.domain.common.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates {@link TenantContextHolder} from the authenticated JWT's {@code tenant_id} claim.
 *
 * <p>Runs inside the Keycloak security chain (after {@code BearerTokenAuthenticationFilter}), so the
 * tenant can only come from a signature-verified token — never from a client-supplied header. The
 * context is cleared in a {@code finally} block so pooled threads cannot leak a tenant.</p>
 */
public class JwtTenantContextFilter extends OncePerRequestFilter {

    static final String TENANT_CLAIM = "tenant_id";
    static final String DEFAULT_TENANT = "default";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String tenant = jwt.getToken().getClaimAsString(TENANT_CLAIM);
            TenantContextHolder.set(new TenantId(
                    tenant == null || tenant.isBlank() ? DEFAULT_TENANT : tenant));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
