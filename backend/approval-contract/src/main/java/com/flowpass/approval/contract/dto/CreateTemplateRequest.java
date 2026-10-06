package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Request to register a new approval template (created in {@code DRAFT}).
 *
 * @param templateKey unique tenant-scoped key; must correspond to a classpath BPMN definition for the
 *                    template to be publishable
 */
public record CreateTemplateRequest(
        @NotBlank @Size(max = 64) String templateKey) implements Serializable {

    private static final long serialVersionUID = 1L;
}
