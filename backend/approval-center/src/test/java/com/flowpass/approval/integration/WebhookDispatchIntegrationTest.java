package com.flowpass.approval.integration;

import com.flowpass.approval.application.outbox.WebhookDispatchHandler;
import com.flowpass.approval.application.query.ListWebhookDeliveriesHandler;
import com.flowpass.approval.application.query.ListWebhookDeliveriesQuery;
import com.flowpass.approval.contract.dto.WebhookDeliveryDTO;
import com.flowpass.approval.domain.common.TenantId;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.flowpass.approval.support.WithAllApprovalAuthorities;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end slice test for the webhook delivery flow against a real PostgreSQL.
 *
 * <p>Boots the application context with Testcontainers and drives {@link WebhookDispatchHandler} through its
 * real MyBatis adapters and the {@link com.flowpass.approval.infrastructure.integration.HttpWebhookDeliverer},
 * posting to a local in-process {@code http} server. Asserts a due {@code PENDING} delivery is sent and flipped
 * to {@code DELIVERED}, and a failing endpoint schedules a retry — invariants only a real DB + HTTP stack can
 * guard (the find-due SQL, the payload join, the ledger update).</p>
 *
 * <p>Requires a local Docker daemon; skips gracefully when unavailable via
 * {@code @Testcontainers(disabledWithoutDocker = true)}.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@WithAllApprovalAuthorities
@Testcontainers(disabledWithoutDocker = true)
class WebhookDispatchIntegrationTest {

    private static final String TENANT = "t_wh";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WebhookDispatchHandler webhookDispatcher;

    @Autowired
    private ListWebhookDeliveriesHandler listWebhookHandler;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpServer server;
    private String endpoint;
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicBoolean failNow = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM webhook_delivery WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM outbox_event WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM webhook_subscription WHERE tenant_id = ?", TENANT);

        received.set(null);
        failNow.set(false);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int code = failNow.get() ? 500 : 200;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
        endpoint = "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Seed a DISPATCHED outbox event plus a PENDING delivery that points at the local hook endpoint. */
    private void seedPendingDelivery(String eventId) {
        jdbc.update("""
                INSERT INTO webhook_subscription (id, tenant_id, event_type, endpoint, enabled)
                VALUES (?, ?, ?, ?, true)
                """, "sub_wh", TENANT, "approval.instance.created", endpoint);
        jdbc.update("""
                INSERT INTO outbox_event (id, event_id, tenant_id, aggregate_id, aggregate_type, type, payload, publish_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'DISPATCHED', now())
                """,
                "oe_wh", eventId, TENANT, "agg_wh", "ApprovalInstance", "approval.instance.created",
                "{\"event\":\"created\"}");
        jdbc.update("""
                INSERT INTO webhook_delivery (id, event_id, tenant_id, endpoint, attempt, status, created_at)
                VALUES (?, ?, ?, ?, 0, 'PENDING', now())
                """, "dlv_wh", eventId, TENANT, endpoint);
    }

    @Test
    void deliversDueWebhookAndMarksDelivered() {
        seedPendingDelivery("evt_wh");

        WebhookDispatchHandler.DispatchSummary summary = webhookDispatcher.dispatch(10);

        assertThat(summary.delivered()).isEqualTo(1);
        // The payload round-trips through Postgres jsonb, which normalises spacing, so assert structurally.
        assertThat(received.get()).contains("\"event\"", "\"created\"");
        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempt FROM webhook_delivery WHERE id = 'dlv_wh'");
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(((Number) row.get("attempt")).intValue()).isZero();
    }

    @Test
    void retriesWhenEndpointReturnsError() {
        failNow.set(true);
        seedPendingDelivery("evt_wh");

        WebhookDispatchHandler.DispatchSummary summary = webhookDispatcher.dispatch(10);

        assertThat(summary.retried()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt, next_retry_at FROM webhook_delivery WHERE id = 'dlv_wh'");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt")).intValue()).isEqualTo(1);
        assertThat(row.get("next_retry_at")).isNotNull();
    }

    @Test
    void listsDeliveredDeliveriesForTenant() {
        seedPendingDelivery("evt_wh");
        webhookDispatcher.dispatch(10);

        List<WebhookDeliveryDTO> delivered = listWebhookHandler.handle(
                new ListWebhookDeliveriesQuery(new TenantId(TENANT), "DELIVERED", null, 20));
        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).eventId()).isEqualTo("evt_wh");
        assertThat(delivered.get(0).status()).isEqualTo("DELIVERED");
        assertThat(delivered.get(0).attempt()).isZero();

        List<WebhookDeliveryDTO> pending = listWebhookHandler.handle(
                new ListWebhookDeliveriesQuery(new TenantId(TENANT), "PENDING", null, 20));
        assertThat(pending).isEmpty();
    }
}
