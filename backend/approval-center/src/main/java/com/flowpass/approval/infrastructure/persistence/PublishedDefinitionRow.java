package com.flowpass.approval.infrastructure.persistence;

/**
 * Persistence shape for the latest published template definition. Infrastructure-only; mapped to the
 * domain {@code PublishedDefinition} value object, never exposed. Field names align to the query
 * aliases via {@code map-underscore-to-camel-case}.
 */
public class PublishedDefinitionRow {

    private String templateId;
    private String templateKey;
    private Integer definitionVersion;
    private Integer formVersion;
    private String checksum;
    private String status;

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }
    public Integer getDefinitionVersion() { return definitionVersion; }
    public void setDefinitionVersion(Integer definitionVersion) { this.definitionVersion = definitionVersion; }
    public Integer getFormVersion() { return formVersion; }
    public void setFormVersion(Integer formVersion) { this.formVersion = formVersion; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
