package com.flowpass.approval.infrastructure.persistence;

import java.math.BigDecimal;

/**
 * Persistence row for {@code approval_instance}. This is an infrastructure-only shape; it is mapped
 * to the domain aggregate, never exposed. Field names align to the table via
 * {@code map-underscore-to-camel-case}.
 */
public class ApprovalInstanceRow {

    private String id;
    private String tenantId;
    private String applicationId;
    private String businessKey;
    private String templateKey;
    private String initiatorType;
    private String initiatorId;
    private BigDecimal amount;
    private String currency;
    private Integer definitionVersion;
    private String status;
    private Long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }
    public String getInitiatorType() { return initiatorType; }
    public void setInitiatorType(String initiatorType) { this.initiatorType = initiatorType; }
    public String getInitiatorId() { return initiatorId; }
    public void setInitiatorId(String initiatorId) { this.initiatorId = initiatorId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Integer getDefinitionVersion() { return definitionVersion; }
    public void setDefinitionVersion(Integer definitionVersion) { this.definitionVersion = definitionVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
