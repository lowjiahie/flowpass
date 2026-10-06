package com.flowpass.approval.application.query;

import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read use case: list all templates of a tenant (admin view).
 *
 * <p>Pure read-only mapping to the public contract DTO; no business rules here.</p>
 */
@Service
public class ListTemplatesHandler {

    private final TemplateRepository templateRepository;

    public ListTemplatesHandler(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional(readOnly = true)
    public List<TemplateDTO> handle(ListTemplatesQuery query) {
        return templateRepository.findAll(query.tenantId()).stream()
                .map(this::toDto)
                .toList();
    }

    private TemplateDTO toDto(ApprovalTemplate template) {
        return new TemplateDTO(template.id(), template.templateKey(), template.status().name(),
                template.latestVersion());
    }
}
