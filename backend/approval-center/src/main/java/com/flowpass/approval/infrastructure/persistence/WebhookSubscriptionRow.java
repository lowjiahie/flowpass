package com.flowpass.approval.infrastructure.persistence;

/**
 * Persistence row for {@code webhook_subscription}. Infrastructure-only shape; mapped to the
 * {@code WebhookSubscription} domain value by the adapter, never exposed. Field names align to the
 * table via {@code map-underscore-to-camel-case}.
 */
public class WebhookSubscriptionRow {

    private String id;
    private String tenantId;
    private String eventType;
    private String endpoint;
    private Boolean enabled;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
