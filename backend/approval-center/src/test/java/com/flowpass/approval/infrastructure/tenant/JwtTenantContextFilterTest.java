package com.flowpass.approval.infrastructure.tenant;

import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JwtTenantContextFilter}: the tenant comes from the verified JWT's {@code tenant_id}
 * claim (never the header), falls back to {@code default}, and is always cleared afterwards.
 */
class JwtTenantContextFilterTest {

    private final JwtTenantContextFilter filter = new JwtTenantContextFilter();
    private final CurrentTenantPort port = new ThreadLocalTenantContextAdapter();

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private Jwt jwtWithTenant(String tenant) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("tenant_id", tenant)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("u-demo")
                .build();
    }

    @Test
    void derivesTenantFromJwtClaim() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtWithTenant("t_42"), List.of()));

        AtomicReference<TenantId> seen = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seen.set(port.currentTenant()));

        assertThat(seen.get()).isEqualTo(new TenantId("t_42"));
    }

    @Test
    void defaultsWhenClaimAbsent() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("u-demo")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        AtomicReference<TenantId> seen = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seen.set(port.currentTenant()));

        assertThat(seen.get()).isEqualTo(new TenantId(JwtTenantContextFilter.DEFAULT_TENANT));
    }

    @Test
    void clearsContextAfterRequest() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtWithTenant("t_42"), List.of()));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(TenantContextHolder.current()).isNull();
    }
}
