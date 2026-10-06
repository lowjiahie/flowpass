package com.flowpass.approval.infrastructure.persistence;

import java.math.BigDecimal;

/**
 * DB row for {@code decision_rule}. Infrastructure-only shape; the JSON columns are mapped to/from the
 * domain by the repository adapter. Field names align to columns via {@code map-underscore-to-camel-case}.
 */
public class DecisionRuleRow {

    private String id;
    private String tenantId;
    private String templateKey;
    private int priority;
    private BigDecimal amountMin;
    private BigDecimal amountMax;
    private String fieldMatches;
    private String approvers;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public void setTemplateKey(String templateKey) {
        this.templateKey = templateKey;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public BigDecimal getAmountMin() {
        return amountMin;
    }

    public void setAmountMin(BigDecimal amountMin) {
        this.amountMin = amountMin;
    }

    public BigDecimal getAmountMax() {
        return amountMax;
    }

    public void setAmountMax(BigDecimal amountMax) {
        this.amountMax = amountMax;
    }

    public String getFieldMatches() {
        return fieldMatches;
    }

    public void setFieldMatches(String fieldMatches) {
        this.fieldMatches = fieldMatches;
    }

    public String getApprovers() {
        return approvers;
    }

    public void setApprovers(String approvers) {
        this.approvers = approvers;
    }
}
