package com.flowpass.approval.application.outbox;

import com.flowpass.approval.application.port.OutboxEventStore;
import com.flowpass.approval.application.port.WebhookDelivererPort;
import com.flowpass.approval.application.port.WebhookDelivery;
import com.flowpass.approval.application.port.WebhookDeliveryStore;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link WebhookDispatchHandler} — the delivery state machine. The transport and stores are mocked. */
@ExtendWith(MockitoExtension.class)
class WebhookDispatchHandlerTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 30_000L;

    @Mock
    private WebhookDeliveryStore deliveryStore;
    @Mock
    private OutboxEventStore outboxEventStore;
    @Mock
    private WebhookDelivererPort deliverer;

    private WebhookDispatchHandler handler() {
        return new WebhookDispatchHandler(deliveryStore, outboxEventStore, deliverer, MAX_ATTEMPTS, RETRY_DELAY_MS);
    }

    private WebhookDelivery delivery(int attempt) {
        return new WebhookDelivery(
                "dlv_1", "evt_1", new TenantId("t_10086"), "https://hook.example.com/event", attempt, "PENDING", Instant.now(), null);
    }

    @Test
    void deliversAndMarksDelivered() {
        WebhookDelivery due = delivery(0);
        when(deliveryStore.findDue(10)).thenReturn(List.of(due));
        when(outboxEventStore.findPayload("evt_1")).thenReturn("{\"event\":\"created\"}");

        WebhookDispatchHandler.DispatchSummary summary = handler().dispatch(10);

        verify(deliverer).deliver(eq(due), eq("{\"event\":\"created\"}"));
        verify(deliveryStore).updateStatus("dlv_1", 0, "DELIVERED", null);
        assertThat(summary.delivered()).isEqualTo(1);
        assertThat(summary.retried()).isZero();
        assertThat(summary.failed()).isZero();
    }

    @Test
    void retriesWhenTransportFails() {
        WebhookDelivery due = delivery(0);
        when(deliveryStore.findDue(10)).thenReturn(List.of(due));
        when(outboxEventStore.findPayload("evt_1")).thenReturn("{\"event\":\"created\"}");
        doThrow(new RuntimeException("boom")).when(deliverer).deliver(eq(due), eq("{\"event\":\"created\"}"));

        WebhookDispatchHandler.DispatchSummary summary = handler().dispatch(10);

        verify(deliveryStore).updateStatus(eq("dlv_1"), eq(1), eq("PENDING"), isNotNull());
        assertThat(summary.delivered()).isZero();
        assertThat(summary.retried()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
    }

    @Test
    void failsPermanentlyAtMaxAttempts() {
        WebhookDelivery due = delivery(2); // nextAttempt = 3 >= maxAttempts
        when(deliveryStore.findDue(10)).thenReturn(List.of(due));
        when(outboxEventStore.findPayload("evt_1")).thenReturn("{\"event\":\"created\"}");
        doThrow(new RuntimeException("boom")).when(deliverer).deliver(eq(due), eq("{\"event\":\"created\"}"));

        WebhookDispatchHandler.DispatchSummary summary = handler().dispatch(10);

        verify(deliveryStore).updateStatus("dlv_1", 3, "FAILED", null);
        assertThat(summary.delivered()).isZero();
        assertThat(summary.retried()).isZero();
        assertThat(summary.failed()).isEqualTo(1);
    }
}
