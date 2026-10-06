package com.flowpass.approval.infrastructure.persistence;

/** DB row for {@code approval_template}. Maps 1:1 to the mapper insert/select. */
public class ApprovalTemplateRow {

    private String draftBpmn;
    private String draftFormSchema;
    private int latestVersion;
    private String id;
    private String tenantId;
    private String templateKey;
    private String status;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDraftBpmn() {
        return draftBpmn;
    }

    public void setDraftBpmn(String draftBpmn) {
        this.draftBpmn = draftBpmn;
    }

    public int getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(int latestVersion) {
        this.latestVersion = latestVersion;
    }

    public String getDraftFormSchema() {
        return draftFormSchema;
    }

    public void setDraftFormSchema(String draftFormSchema) {
        this.draftFormSchema = draftFormSchema;
    }
}
