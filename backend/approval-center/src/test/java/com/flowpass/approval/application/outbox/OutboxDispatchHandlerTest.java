package com.flowpass.approval.application.outbox;

import com.flowpass.approval.application.port.OutboxEventStore;
import com.flowpass.approval.application.port.WebhookDelivery;
import com.flowpass.approval.application.port.WebhookDeliveryStore;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.integration.WebhookSubscription;
import com.flowpass.approval.domain.integration.WebhookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Use-case tests for {@link OutboxDispatchHandler} — matching subscriptions to pending events, enqueuing
 * a delivery per subscription, and leaving subscription-less events pending. Ports are mocked.
 */
@ExtendWith(MockitoExtension.class)
class OutboxDispatchHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");

    @Mock
    private OutboxEventStore outboxEventStore;
    @Mock
    private WebhookSubscriptionRepository subscriptionRepository;
    @Mock
    private WebhookDeliveryStore deliveryStore;

    private OutboxDispatchHandler handler() {
        return new OutboxDispatchHandler(outboxEventStore, subscriptionRepository, deliveryStore);
    }

    private OutboxEventStore.PendingOutboxEvent pendingEvent(String eventId, String type) {
        return new OutboxEventStore.PendingOutboxEvent(eventId, tenant, type, "{}");
    }

    private WebhookSubscription subscription(String id, String endpoint) {
        return new WebhookSubscription(id, tenant, "approval.instance.created", endpoint, true);
    }

    @Test
    void enqueuesDeliveryPerMatchedSubscriptionAndMarksDispatched() {
        OutboxEventStore.PendingOutboxEvent event = pendingEvent("evt-1", "approval.instance.created");
        when(outboxEventStore.findPending(10)).thenReturn(List.of(event));
        when(subscriptionRepository.findEnabled(tenant, "approval.instance.created"))
                .thenReturn(List.of(subscription("sub-1", "https://a.example/hook")));

        OutboxDispatchHandler.DispatchSummary summary = handler().dispatch(10);

        assertThat(summary.eventsDrained()).isEqualTo(1);
        assertThat(summary.deliveriesEnqueued()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WebhookDelivery>> captor = ArgumentCaptor.forClass(List.class);
        verify(deliveryStore).recordAll(captor.capture());
        List<WebhookDelivery> deliveries = captor.getValue();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).eventId()).isEqualTo("evt-1");
        assertThat(deliveries.get(0).tenantId()).isEqualTo(tenant);
        assertThat(deliveries.get(0).endpoint()).isEqualTo("https://a.example/hook");
        assertThat(deliveries.get(0).attempt()).isZero();
        assertThat(deliveries.get(0).status()).isEqualTo("PENDING");
        verify(outboxEventStore).markDispatched("evt-1");
    }

    @Test
    void leavesEventPendingWhenNoSubscriptionMatches() {
        OutboxEventStore.PendingOutboxEvent event = pendingEvent("evt-2", "approval.instance.created");
        when(outboxEventStore.findPending(10)).thenReturn(List.of(event));
        when(subscriptionRepository.findEnabled(tenant, "approval.instance.created")).thenReturn(List.of());

        OutboxDispatchHandler.DispatchSummary summary = handler().dispatch(10);

        assertThat(summary.eventsDrained()).isZero();
        assertThat(summary.deliveriesEnqueued()).isZero();
        verify(deliveryStore, never()).recordAll(org.mockito.ArgumentMatchers.<List<WebhookDelivery>>any());
        verify(outboxEventStore, never()).markDispatched("evt-2");
    }

    @Test
    void enqueuesOneDeliveryPerMatchingSubscription() {
        OutboxEventStore.PendingOutboxEvent event = pendingEvent("evt-3", "approval.instance.created");
        when(outboxEventStore.findPending(10)).thenReturn(List.of(event));
        when(subscriptionRepository.findEnabled(tenant, "approval.instance.created"))
                .thenReturn(List.of(subscription("sub-1", "https://a.example/hook"),
                        subscription("sub-2", "https://b.example/hook")));

        OutboxDispatchHandler.DispatchSummary summary = handler().dispatch(10);

        assertThat(summary.eventsDrained()).isEqualTo(1);
        assertThat(summary.deliveriesEnqueued()).isEqualTo(2);
        verify(outboxEventStore).markDispatched("evt-3");
    }
}
