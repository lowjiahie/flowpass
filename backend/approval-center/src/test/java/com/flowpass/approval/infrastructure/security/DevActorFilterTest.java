package com.flowpass.approval.infrastructure.security;

import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.domain.common.ApproverRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DevActorFilterTest {

    private final DevActorFilter filter = new DevActorFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Authentication run(MockHttpServletRequest request) throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return seen.get();
    }

    @Test
    void buildsActorFromHeadersWithAllAuthoritiesByDefault() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Actor-Id", "u_2");

        Authentication auth = run(request);

        assertThat(auth.getPrincipal()).isEqualTo(new ApproverRef("USER", "u_2"));
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrderElementsOf(Permissions.ALL);
    }

    @Test
    void honoursTypeAndExplicitAuthorities() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Actor-Id", "svc");
        request.addHeader("X-Actor-Type", "SYSTEM");
        request.addHeader("X-Actor-Authorities", "approval.request.read, approval.request.create");

        Authentication auth = run(request);

        assertThat(auth.getPrincipal()).isEqualTo(new ApproverRef("SYSTEM", "svc"));
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("approval.request.read", "approval.request.create");
    }

    @Test
    void staysUnauthenticatedWithoutActorHeader() throws Exception {
        assertThat(run(new MockHttpServletRequest())).isNull();
    }

    @Test
    void clearsContextAfterTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Actor-Id", "u_2");
        run(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
