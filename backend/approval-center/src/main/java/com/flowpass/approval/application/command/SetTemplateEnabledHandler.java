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
 * Disable / enable use case: stop (or resume) accepting new instances of a published template. Versions
 * and running instances are untouched — an instance already started keeps running on its pinned version.
 */
@Service
public class SetTemplateEnabledHandler {

    private final TemplateRepository templateRepository;

    public SetTemplateEnabledHandler(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional
    public TemplateDTO handle(SetTemplateEnabledCommand cmd) {
        ApprovalTemplate template = templateRepository.findByKey(cmd.tenantId(), cmd.templateKey())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Template not found: " + cmd.templateKey()));
        try {
            if (cmd.enabled()) {
                template.enable();
            } else {
                template.disable();
            }
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }
        templateRepository.updateStatus(template);
        return new TemplateDTO(template.id(), template.templateKey(), template.status().name(),
                template.latestVersion());
    }
}
