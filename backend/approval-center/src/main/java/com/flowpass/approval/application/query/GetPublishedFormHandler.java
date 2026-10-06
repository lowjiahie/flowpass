package com.flowpass.approval.application.query;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.contract.dto.PublishedFormDTO;
import com.flowpass.approval.domain.definition.DefinitionRepository;
import com.flowpass.approval.domain.definition.PublishedDefinition;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read use case for applicants: the form (JSON Schema) a new application would be validated against — that of the
 * latest published version of an enabled template. Lets a client render and pre-validate the form with the very
 * rules the server will enforce.
 */
@Service
public class GetPublishedFormHandler {

    private final DefinitionRepository definitionRepository;

    public GetPublishedFormHandler(DefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_CREATE + "')")
    @Transactional(readOnly = true)
    public PublishedFormDTO handle(GetPublishedFormQuery query) {
        PublishedDefinition definition = definitionRepository.findPublished(query.tenantId(), query.templateKey())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Template not published: " + query.templateKey()));
        String schema = definitionRepository.findFormSchema(query.tenantId(), query.templateKey(),
                definition.definitionVersion()).orElse(null);
        return new PublishedFormDTO(definition.templateKey(), definition.definitionVersion(), schema);
    }
}
