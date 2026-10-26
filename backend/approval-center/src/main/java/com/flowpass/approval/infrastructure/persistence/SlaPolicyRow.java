package com.flowpass.approval.infrastructure.persistence;

/** DB row for {@code sla_policy}. */
public class SlaPolicyRow {

    private String tenantId;
    private String templateKey;
    private int dueAfterHours;
    private Integer remindBeforeHours;
    private String onOverdue;
    private String escalateToUser;

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

    public int getDueAfterHours() {
        return dueAfterHours;
    }

    public void setDueAfterHours(int dueAfterHours) {
        this.dueAfterHours = dueAfterHours;
    }

    public Integer getRemindBeforeHours() {
        return remindBeforeHours;
    }

    public void setRemindBeforeHours(Integer remindBeforeHours) {
        this.remindBeforeHours = remindBeforeHours;
    }

    public String getOnOverdue() {
        return onOverdue;
    }

    public void setOnOverdue(String onOverdue) {
        this.onOverdue = onOverdue;
    }

    public String getEscalateToUser() {
        return escalateToUser;
    }

    public void setEscalateToUser(String escalateToUser) {
        this.escalateToUser = escalateToUser;
    }
}
