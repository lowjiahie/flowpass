package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.contract.dto.SlaPolicyDTO;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.TemplateRepository;
import com.flowpass.approval.domain.sla.OverdueAction;
import com.flowpass.approval.domain.sla.SlaPolicy;
import com.flowpass.approval.domain.sla.SlaPolicyRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin use cases for a template's SLA policy: set, read, remove. A policy only shapes tasks created after it is
 * saved — open tasks keep the deadlines they were given — and is not versioned with the template.
 */
@Service
public class SlaPolicyHandlers {

    private final SlaPolicyRepository policies;
    private final TemplateRepository templates;

    public SlaPolicyHandlers(SlaPolicyRepository policies, TemplateRepository templates) {
        this.policies = policies;
        this.templates = templates;
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional
    public SlaPolicyDTO save(SaveSlaPolicyCommand cmd) {
        requireTemplate(cmd.tenantId(), cmd.templateKey());
        SlaPolicy policy;
        try {
            policy = SlaPolicy.of(cmd.tenantId(), cmd.templateKey(), cmd.dueAfterHours(), cmd.remindBeforeHours(),
                    OverdueAction.valueOf(cmd.onOverdue() == null ? "" : cmd.onOverdue().trim()), cmd.escalateToUser());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        policies.save(policy);
        return toDto(policy);
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional(readOnly = true)
    public SlaPolicyDTO get(TenantId tenantId, String templateKey) {
        requireTemplate(tenantId, templateKey);
        return policies.find(tenantId, templateKey).map(SlaPolicyHandlers::toDto)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Template " + templateKey + " has no SLA policy"));
    }

    @PreAuthorize("hasAuthority('" + Permissions.TEMPLATE_MANAGE + "')")
    @Transactional
    public void delete(TenantId tenantId, String templateKey) {
        requireTemplate(tenantId, templateKey);
        policies.delete(tenantId, templateKey);
    }

    private void requireTemplate(TenantId tenantId, String templateKey) {
        templates.findByKey(tenantId, templateKey).orElseThrow(() -> new BusinessException(
                BusinessErrorCode.TEMPLATE_NOT_FOUND, "Template not found: " + templateKey));
    }

    private static SlaPolicyDTO toDto(SlaPolicy policy) {
        return new SlaPolicyDTO(policy.templateKey(), policy.dueAfterHours(), policy.remindBeforeHours(),
                policy.onOverdue().name(), policy.escalateToUser());
    }
}
