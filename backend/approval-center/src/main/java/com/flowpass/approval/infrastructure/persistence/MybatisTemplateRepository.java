package com.flowpass.approval.infrastructure.persistence;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import com.flowpass.approval.domain.definition.TemplateStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing the {@link TemplateRepository} port on top of MyBatis. Maps the aggregate to
 * {@code approval_template} and publishes the immutable {@code process_definition} snapshot.
 */
@Repository
public class MybatisTemplateRepository implements TemplateRepository {

    private final TemplateMapper mapper;

    public MybatisTemplateRepository(TemplateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ApprovalTemplate> findByKey(TenantId tenantId, String templateKey) {
        ApprovalTemplateRow row = mapper.findByKey(tenantId.value(), templateKey);
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public List<ApprovalTemplate> findAll(TenantId tenantId) {
        return mapper.findAll(tenantId.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void save(ApprovalTemplate template) {
        mapper.insert(toRow(template));
    }

    @Override
    public void saveDraft(ApprovalTemplate template) {
        mapper.updateDraft(template.id(), template.draftBpmn());
    }

    @Override
    public void saveFormSchemaDraft(ApprovalTemplate template) {
        mapper.updateDraftFormSchema(template.id(), template.draftFormSchema());
    }

    @Override
    public void markPublished(ApprovalTemplate template) {
        int updated = mapper.markPublished(template.id(), template.status().name(), template.latestVersion());
        if (updated == 0) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "Concurrent publish on template " + template.id());
        }
    }

    @Override
    public void updateStatus(ApprovalTemplate template) {
        mapper.updateStatus(template.id(), template.status().name());
    }

    @Override
    public void saveDefinition(String templateId, int version, String bpmnXml, String checksum,
                               String formSchema, ApproverRef publishedBy) {
        ProcessDefinitionRow row = new ProcessDefinitionRow();
        row.setId("pd_" + UUID.randomUUID());
        row.setTemplateId(templateId);
        row.setVersion(version);
        row.setBpmnXml(bpmnXml);
        row.setChecksum(checksum);
        row.setPublishedByType(publishedBy.type());
        row.setPublishedById(publishedBy.id());
        try {
            mapper.insertDefinition(row);
            if (formSchema != null) {
                mapper.insertFormSchema("fs_" + UUID.randomUUID(), templateId, version, formSchema);
            }
        } catch (DuplicateKeyException e) {
            // (template, version) is unique: another publish took this version number first.
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "Concurrent publish on template " + templateId);
        }
    }

    private ApprovalTemplate toDomain(ApprovalTemplateRow row) {
        return ApprovalTemplate.hydrate(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getTemplateKey(),
                TemplateStatus.valueOf(row.getStatus()),
                row.getDraftBpmn(),
                row.getDraftFormSchema(),
                row.getLatestVersion());
    }

    private ApprovalTemplateRow toRow(ApprovalTemplate template) {
        ApprovalTemplateRow row = new ApprovalTemplateRow();
        row.setId(template.id());
        row.setTenantId(template.tenantId().value());
        row.setTemplateKey(template.templateKey());
        row.setStatus(template.status().name());
        row.setDraftBpmn(template.draftBpmn());
        row.setDraftFormSchema(template.draftFormSchema());
        row.setLatestVersion(template.latestVersion());
        return row;
    }
}
