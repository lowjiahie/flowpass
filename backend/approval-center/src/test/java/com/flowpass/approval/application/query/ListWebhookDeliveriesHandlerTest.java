package com.flowpass.approval.application.query;

import com.flowpass.approval.application.port.WebhookDelivery;
import com.flowpass.approval.application.port.WebhookDeliveryStore;
import com.flowpass.approval.contract.dto.WebhookDeliveryDTO;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ListWebhookDeliveriesHandler} — paging and DTO mapping. The port is mocked. */
@ExtendWith(MockitoExtension.class)
class ListWebhookDeliveriesHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");
    private final Instant created = Instant.now();
    private final Instant nextRetry = Instant.now().plusSeconds(30);

    @Mock
    private WebhookDeliveryStore deliveryStore;

    private ListWebhookDeliveriesHandler handler() {
        return new ListWebhookDeliveriesHandler(deliveryStore);
    }

    @Test
    void listsAndMapsToDtoWithStatusFilter() {
        WebhookDelivery webhook = new WebhookDelivery(
                "dlv_1", "evt_1", tenant, "https://hook.example.com/event", 2, "PENDING", created, nextRetry);
        when(deliveryStore.findPage(eq(tenant), eq("PENDING"), eq("dlv_0"), eq(20)))
                .thenReturn(List.of(webhook));

        List<WebhookDeliveryDTO> result = handler().handle(
                new ListWebhookDeliveriesQuery(tenant, "PENDING", "dlv_0", 20));

        assertThat(result).hasSize(1);
        WebhookDeliveryDTO dto = result.get(0);
        assertThat(dto.id()).isEqualTo("dlv_1");
        assertThat(dto.eventId()).isEqualTo("evt_1");
        assertThat(dto.tenantId()).isEqualTo("t_10086");
        assertThat(dto.endpoint()).isEqualTo("https://hook.example.com/event");
        assertThat(dto.attempt()).isEqualTo(2);
        assertThat(dto.status()).isEqualTo("PENDING");
        assertThat(dto.createdAt()).isEqualTo(created);
        assertThat(dto.nextRetryAt()).isEqualTo(nextRetry);
    }

    @Test
    void passesNullsWhenFilterAbsent() {
        when(deliveryStore.findPage(eq(tenant), isNull(), isNull(), eq(20))).thenReturn(List.of());

        List<WebhookDeliveryDTO> result = handler().handle(new ListWebhookDeliveriesQuery(tenant, null, null, 20));

        assertThat(result).isEmpty();
    }
}
