package com.flowpass.approval.infrastructure.persistence;

import java.time.Instant;

/**
 * Persistence row for {@code webhook_delivery}. Infrastructure-only shape; built by the outbox dispatch
 * adapter and never exposed. Field names align to the table via {@code map-underscore-to-camel-case}.
 */
public class WebhookDeliveryRow {

    private String id;
    private String eventId;
    private String tenantId;
    private String endpoint;
    private Integer attempt;
    private String status;
    private Instant nextRetryAt;
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
