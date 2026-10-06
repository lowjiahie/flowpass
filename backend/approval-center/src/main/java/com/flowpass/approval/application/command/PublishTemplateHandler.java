package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.application.port.BpmnDefinitionSource;
import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Publish-template use case. Snapshots the template's definition as the <em>next immutable version</em> and
 * makes the template runnable — in one local transaction. A template can be published any number of times;
 * every publish is a new version, and running instances keep the version they started on.
 *
 * <p>The definition is the template's draft; a template without a draft publishes the platform's built-in
 * definition for its key (the {@link BpmnDefinitionSource} port), if there is one. The draft form schema (if any)
 * is snapshotted with it under the same version number. The aggregate validates
 * it against the flow contract and owns the version counter; the repository guards the counter with a
 * compare-and-set so two concurrent publishes cannot both claim the same version.</p>
 */
@Service
public class PublishTemplateHandler {

    private final TemplateRepository templateRepository;
    private final BpmnDefinitionSource bpmnDefinitionSource;

    public PublishTemplateHandler(TemplateRepository templateRepository, BpmnDefinitionSource bpmnDefinitionSource) {
        this.templateRepository = templateRepository;
        this.bpmnDefinitionSource = bpmnDefinitionSource;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional
    public TemplateDTO handle(PublishTemplateCommand cmd) {
        ApprovalTemplate template = templateRepository.findByKey(cmd.tenantId(), cmd.templateKey())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Template not found: " + cmd.templateKey()));

        String bpmnXml = template.draftBpmn();
        if (bpmnXml == null) {
            try {
                bpmnXml = bpmnDefinitionSource.load(cmd.templateKey());
            } catch (IllegalStateException e) {
                throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED,
                        "No draft saved and no built-in definition for template " + cmd.templateKey());
            }
        }

        int version;
        try {
            version = template.publish(bpmnXml);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }

        templateRepository.saveDefinition(template.id(), version, bpmnXml, checksum(bpmnXml),
                template.draftFormSchema(), cmd.actor());
        templateRepository.markPublished(template);

        return new TemplateDTO(template.id(), template.templateKey(), template.status().name(),
                template.latestVersion());
    }

    private String checksum(String bpmnXml) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bpmnXml.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
