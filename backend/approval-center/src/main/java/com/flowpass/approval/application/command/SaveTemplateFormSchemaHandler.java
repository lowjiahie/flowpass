package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.application.port.FormValidationPort;
import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Save-form-schema use case: replace (or remove) the editable draft of a template's application form.
 *
 * <p>The schema is tenant-authored, so it is vetted through the {@link FormValidationPort} first (valid, supported
 * dialect, self-contained, no regular expressions, bounded) and stored in canonical form; nothing is stored when it
 * is refused. The draft only takes effect when the template is published — it becomes that version's immutable
 * form schema and never changes what a running instance was validated against.</p>
 */
@Service
public class SaveTemplateFormSchemaHandler {

    private final TemplateRepository templateRepository;
    private final FormValidationPort formValidation;

    public SaveTemplateFormSchemaHandler(TemplateRepository templateRepository, FormValidationPort formValidation) {
        this.templateRepository = templateRepository;
        this.formValidation = formValidation;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional
    public TemplateDTO handle(SaveTemplateFormSchemaCommand cmd) {
        ApprovalTemplate template = templateRepository.findByKey(cmd.tenantId(), cmd.templateKey())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Template not found: " + cmd.templateKey()));
        String canonical = null;
        if (cmd.schema() != null) {
            try {
                canonical = formValidation.checkSchema(cmd.schema());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED, e.getMessage());
            }
        }
        try {
            template.saveFormSchema(canonical);
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }
        templateRepository.saveFormSchemaDraft(template);
        return new TemplateDTO(template.id(), template.templateKey(), template.status().name(),
                template.latestVersion());
    }
}
