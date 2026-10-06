package com.flowpass.approval.infrastructure.persistence;

/**
 * Persistence row for {@code idempotency_record}. Infrastructure-only shape; built by the idempotency
 * adapter and never exposed. The composite unique key {@code (tenant, application, key)} is the
 * concurrency guard for write requests.
 */
public class IdempotencyRecordRow {

    private String id;
    private String tenantId;
    private String applicationId;
    private String idemKey;
    private String responseRef;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getIdemKey() { return idemKey; }
    public void setIdemKey(String idemKey) { this.idemKey = idemKey; }
    public String getResponseRef() { return responseRef; }
    public void setResponseRef(String responseRef) { this.responseRef = responseRef; }
}
