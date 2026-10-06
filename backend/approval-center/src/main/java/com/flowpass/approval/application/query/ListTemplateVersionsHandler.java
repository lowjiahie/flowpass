package com.flowpass.approval.application.query;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.contract.dto.TemplateVersionDTO;
import com.flowpass.approval.domain.definition.DefinitionRepository;
import com.flowpass.approval.domain.definition.TemplateRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read use case: the published versions of a template, newest first (tenant-scoped). */
@Service
public class ListTemplateVersionsHandler {

    private final TemplateRepository templateRepository;
    private final DefinitionRepository definitionRepository;

    public ListTemplateVersionsHandler(TemplateRepository templateRepository,
                                       DefinitionRepository definitionRepository) {
        this.templateRepository = templateRepository;
        this.definitionRepository = definitionRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional(readOnly = true)
    public List<TemplateVersionDTO> handle(ListTemplateVersionsQuery query) {
        templateRepository.findByKey(query.tenantId(), query.templateKey())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Template not found: " + query.templateKey()));
        return definitionRepository.findVersions(query.tenantId(), query.templateKey()).stream()
                .map(v -> new TemplateVersionDTO(v.version(), v.checksum(),
                        v.publishedAt() == null ? null : v.publishedAt().toString(),
                        v.publishedByType(), v.publishedById()))
                .toList();
    }
}
