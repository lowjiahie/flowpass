package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Register-template use case. Creates a new {@code DRAFT} template, rejecting a duplicate key for the
 * tenant. No business rules live here — the aggregate owns its state; this handler only wires the
 * repository and maps to the public contract DTO.
 */
@Service
public class CreateTemplateHandler {

    private final TemplateRepository templateRepository;

    public CreateTemplateHandler(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional
    public TemplateDTO handle(CreateTemplateCommand cmd) {
        if (templateRepository.findByKey(cmd.tenantId(), cmd.templateKey()).isPresent()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "Template already exists: " + cmd.templateKey());
        }
        ApprovalTemplate template = ApprovalTemplate.start(cmd.tenantId(), cmd.templateKey());
        templateRepository.save(template);
        return toDto(template);
    }

    private TemplateDTO toDto(ApprovalTemplate template) {
        return new TemplateDTO(template.id(), template.templateKey(), template.status().name(),
                template.latestVersion());
    }
}
