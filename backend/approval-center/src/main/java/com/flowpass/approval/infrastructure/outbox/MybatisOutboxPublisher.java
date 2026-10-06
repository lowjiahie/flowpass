package com.flowpass.approval.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowpass.approval.application.port.OutboxPublisherPort;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.event.DomainEvent;
import com.flowpass.approval.infrastructure.persistence.OutboxEventRow;
import com.flowpass.approval.infrastructure.persistence.OutboxMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Adapter implementing the {@link OutboxPublisherPort} on top of the {@code outbox_event} table.
 *
 * <p>Each drained domain event becomes a {@code PENDING} row with a fresh {@code eventId} and a
 * JSON payload. Publishing to webhooks (the async worker) is a later milestone; this adapter only
 * persists transactionally.</p>
 */
@Repository
public class MybatisOutboxPublisher implements OutboxPublisherPort {

    private final OutboxMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisOutboxPublisher(OutboxMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(TenantId tenantId, String aggregateId, String aggregateType, List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (DomainEvent event : events) {
            OutboxEventRow row = new OutboxEventRow();
            row.setId(UUID.randomUUID().toString());
            row.setEventId(UUID.randomUUID().toString());
            row.setTenantId(tenantId.value());
            row.setAggregateId(aggregateId);
            row.setAggregateType(aggregateType);
            row.setType(eventType(event.getClass()));
            row.setPayload(toJson(event));
            row.setPublishStatus("PENDING");
            mapper.insert(row);
        }
    }

    /** {@code ApprovalInstanceCreated} → {@code approval.instance.created} (per the event contract). */
    private String eventType(Class<?> type) {
        String dotted = type.getSimpleName().replaceAll("(?<=[a-z0-9])(?=[A-Z])", ".");
        return dotted.toLowerCase();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize domain event payload", e);
        }
    }
}
