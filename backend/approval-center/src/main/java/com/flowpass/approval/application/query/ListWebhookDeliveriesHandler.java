package com.flowpass.approval.application.query;

import com.flowpass.approval.application.port.WebhookDelivery;
import com.flowpass.approval.application.port.WebhookDeliveryStore;
import com.flowpass.approval.contract.dto.WebhookDeliveryDTO;
import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read use case: page webhook deliveries for a tenant (admin/ops view), optionally filtered by status.
 *
 * <p>Read-only mapping to the public contract DTO; no business rules here. The ledger is the source of
 * truth for whether a delivery was delivered, is awaiting retry, or failed.</p>
 */
@Service
public class ListWebhookDeliveriesHandler {

    private final WebhookDeliveryStore deliveryStore;

    public ListWebhookDeliveriesHandler(WebhookDeliveryStore deliveryStore) {
        this.deliveryStore = deliveryStore;
    }

    @PreAuthorize("hasAuthority('" + Permissions.WEBHOOK_MANAGE + "')")
    @Transactional(readOnly = true)
    public List<WebhookDeliveryDTO> handle(ListWebhookDeliveriesQuery query) {
        return deliveryStore.findPage(query.tenantId(), query.status(), query.cursor(), query.limit())
                .stream()
                .map(this::toDto)
                .toList();
    }

    private WebhookDeliveryDTO toDto(WebhookDelivery delivery) {
        return new WebhookDeliveryDTO(
                delivery.id(),
                delivery.eventId(),
                delivery.tenantId().value(),
                delivery.endpoint(),
                delivery.attempt(),
                delivery.status(),
                delivery.createdAt(),
                delivery.nextRetryAt());
    }
}
