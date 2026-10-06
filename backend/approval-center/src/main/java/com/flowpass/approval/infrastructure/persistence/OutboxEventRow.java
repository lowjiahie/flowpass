package com.flowpass.approval.infrastructure.persistence;

/**
 * Persistence row for {@code outbox_event}. Infrastructure-only shape; built by the outbox adapter and
 * never exposed. {@code payload} holds the JSON-serialised domain event.
 */
public class OutboxEventRow {

    private String id;
    private String eventId;
    private String tenantId;
    private String aggregateId;
    private String aggregateType;
    private String type;
    private String payload;
    private String publishStatus;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getPublishStatus() { return publishStatus; }
    public void setPublishStatus(String publishStatus) { this.publishStatus = publishStatus; }
}
