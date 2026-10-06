package com.flowpass.approval.infrastructure.persistence;

import java.time.Instant;

/** DB row for {@code process_definition}. Maps 1:1 to the mapper insert. */
public class ProcessDefinitionRow {

    private String publishedByType;
    private String publishedById;
    private Instant createdAt;
    private String id;
    private String templateId;
    private int version;
    private String bpmnXml;
    private String checksum;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getBpmnXml() {
        return bpmnXml;
    }

    public void setBpmnXml(String bpmnXml) {
        this.bpmnXml = bpmnXml;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getPublishedByType() {
        return publishedByType;
    }

    public void setPublishedByType(String publishedByType) {
        this.publishedByType = publishedByType;
    }

    public String getPublishedById() {
        return publishedById;
    }

    public void setPublishedById(String publishedById) {
        this.publishedById = publishedById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
