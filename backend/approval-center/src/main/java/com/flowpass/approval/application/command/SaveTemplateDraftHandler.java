package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Save-draft use case: replace the editable draft of a template. The aggregate validates the definition
 * against the platform flow contract and refuses a disabled template; the draft does not affect any
 * published version or running instance.
 */
@Service
public class SaveTemplateDraftHandler {

    private final TemplateRepository templateRepository;

    public SaveTemplateDraftHandler(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional
    public TemplateDTO handle(SaveTemplateDraftCommand cmd) {
        ApprovalTemplate template = templateRepository.findByKey(cmd.tenantId(), cmd.templateKey())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Template not found: " + cmd.templateKey()));
        try {
            template.saveDraft(cmd.bpmnXml());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }
        templateRepository.saveDraft(template);
        return new TemplateDTO(template.id(), template.templateKey(), template.status().name(),
                template.latestVersion());
    }
}
