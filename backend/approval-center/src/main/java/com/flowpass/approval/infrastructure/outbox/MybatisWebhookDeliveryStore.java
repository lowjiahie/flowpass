package com.flowpass.approval.infrastructure.outbox;

import com.flowpass.approval.application.port.WebhookDelivery;
import com.flowpass.approval.application.port.WebhookDeliveryStore;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.infrastructure.persistence.WebhookDeliveryMapper;
import com.flowpass.approval.infrastructure.persistence.WebhookDeliveryRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Adapter implementing the {@link WebhookDeliveryStore} port on top of MyBatis.
 *
 * <p>Converts the application-level delivery records into {@code webhook_delivery} rows ({@code PENDING}
 * with {@code attempt = 0}), reads the due rows for the dispatcher, and updates them after each attempt.</p>
 */
@Repository
public class MybatisWebhookDeliveryStore implements WebhookDeliveryStore {

    private final WebhookDeliveryMapper mapper;

    public MybatisWebhookDeliveryStore(WebhookDeliveryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void recordAll(List<WebhookDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return;
        }
        mapper.insertAll(deliveries.stream()
                .map(this::toRow)
                .toList());
    }

    @Override
    public List<WebhookDelivery> findDue(int limit) {
        return mapper.findDue(limit).stream()
                .map(this::toDelivery)
                .toList();
    }

    @Override
    public void updateStatus(String id, int attempt, String status, Instant nextRetryAt) {
        mapper.updateStatus(id, attempt, status, nextRetryAt);
    }

    @Override
    public List<WebhookDelivery> findPage(TenantId tenantId, String status, String cursor, int limit) {
        return mapper.findPage(tenantId.value(), status, cursor, limit).stream()
                .map(this::toDelivery)
                .toList();
    }

    private WebhookDelivery toDelivery(WebhookDeliveryRow row) {
        return new WebhookDelivery(
                row.getId(),
                row.getEventId(),
                new TenantId(row.getTenantId()),
                row.getEndpoint(),
                row.getAttempt(),
                row.getStatus(),
                row.getCreatedAt(),
                row.getNextRetryAt());
    }

    private WebhookDeliveryRow toRow(WebhookDelivery delivery) {
        WebhookDeliveryRow row = new WebhookDeliveryRow();
        row.setId(delivery.id());
        row.setEventId(delivery.eventId());
        row.setTenantId(delivery.tenantId().value());
        row.setEndpoint(delivery.endpoint());
        row.setAttempt(delivery.attempt());
        row.setStatus(delivery.status());
        row.setCreatedAt(delivery.createdAt());
        return row;
    }
}
