package com.flowpass.approval.infrastructure.tenant;

import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.domain.common.TenantId;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the request-scoped tenant context: the filter publishes the resolved tenant for the
 * duration of the request, defaults to {@code default}, and always clears it afterwards — the pooled
 * thread must never keep a tenant across requests.
 */
class TenantResolutionFilterTest {

    private final TenantResolutionFilter filter = new TenantResolutionFilter();
    private final CurrentTenantPort port = new ThreadLocalTenantContextAdapter();

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    private MockHttpServletRequest requestWithTenant(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/tasks");
        if (headerValue != null) {
            request.addHeader(TenantResolutionFilter.TENANT_HEADER, headerValue);
        }
        return request;
    }

    @Test
    void publishesHeaderTenantDuringTheRequest() throws Exception {
        AtomicReference<TenantId> seen = new AtomicReference<>();

        filter.doFilter(requestWithTenant("t_10086"), new MockHttpServletResponse(),
                (req, res) -> seen.set(port.currentTenant()));

        assertThat(seen.get()).isEqualTo(new TenantId("t_10086"));
    }

    @Test
    void defaultsToDefaultTenantWhenHeaderAbsent() throws Exception {
        AtomicReference<TenantId> seen = new AtomicReference<>();

        filter.doFilter(requestWithTenant(null), new MockHttpServletResponse(),
                (req, res) -> seen.set(port.currentTenant()));

        assertThat(seen.get()).isEqualTo(new TenantId(TenantResolutionFilter.DEFAULT_TENANT));
    }

    @Test
    void defaultsToDefaultTenantWhenHeaderBlank() throws Exception {
        AtomicReference<TenantId> seen = new AtomicReference<>();

        filter.doFilter(requestWithTenant("   "), new MockHttpServletResponse(),
                (req, res) -> seen.set(port.currentTenant()));

        assertThat(seen.get()).isEqualTo(new TenantId(TenantResolutionFilter.DEFAULT_TENANT));
    }

    @Test
    void clearsContextAfterTheRequest() throws Exception {
        filter.doFilter(requestWithTenant("t_10086"), new MockHttpServletResponse(),
                (req, res) -> { });

        assertThatThrownBy(port::currentTenant).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clearsContextEvenWhenTheChainThrows() {
        MockHttpServletRequest request = requestWithTenant("t_10086");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    throw new ServletException("boom");
                }))
                .isInstanceOf(ServletException.class);

        assertThatThrownBy(port::currentTenant).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adapterFailsFastWithoutRequestContext() {
        assertThatThrownBy(port::currentTenant).isInstanceOf(IllegalStateException.class);
    }
}
