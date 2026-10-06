package com.flowpass.approval.application.query;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.contract.dto.TemplateDetailDTO;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read use case: one template with its editable draft (tenant-scoped). */
@Service
public class GetTemplateHandler {

    private final TemplateRepository templateRepository;

    public GetTemplateHandler(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional(readOnly = true)
    public TemplateDetailDTO handle(GetTemplateQuery query) {
        ApprovalTemplate template = templateRepository.findByKey(query.tenantId(), query.templateKey())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Template not found: " + query.templateKey()));
        return new TemplateDetailDTO(template.id(), template.templateKey(), template.status().name(),
                template.latestVersion(), template.draftBpmn(), template.draftFormSchema());
    }
}
