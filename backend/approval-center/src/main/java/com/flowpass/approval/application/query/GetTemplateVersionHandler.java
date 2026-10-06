package com.flowpass.approval.application.query;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.contract.dto.TemplateVersionDetailDTO;
import com.flowpass.approval.domain.definition.DefinitionRepository;
import com.flowpass.approval.domain.definition.TemplateVersion;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read use case: one published version of a template with its definition (tenant-scoped). */
@Service
public class GetTemplateVersionHandler {

    private final DefinitionRepository definitionRepository;

    public GetTemplateVersionHandler(DefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional(readOnly = true)
    public TemplateVersionDetailDTO handle(GetTemplateVersionQuery query) {
        TemplateVersion version = definitionRepository.findVersions(query.tenantId(), query.templateKey()).stream()
                .filter(v -> v.version() == query.version())
                .findFirst()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Version " + query.version() + " of template " + query.templateKey() + " not found"));
        String bpmn = definitionRepository.findBpmn(query.tenantId(), query.templateKey(), query.version())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Version " + query.version() + " of template " + query.templateKey() + " not found"));
        String formSchema = definitionRepository.findFormSchema(query.tenantId(), query.templateKey(), query.version())
                .orElse(null);
        return new TemplateVersionDetailDTO(version.version(), version.checksum(), bpmn, formSchema);
    }
}
