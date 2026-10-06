package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * Admin detail of a template, including its editable drafts.
 *
 * @param draftBpmn       the draft process definition, or null when none was saved
 * @param draftFormSchema the draft form schema as JSON text, or null when the form is unconstrained
 */
public record TemplateDetailDTO(String id, String templateKey, String status, int latestVersion, String draftBpmn,
                                String draftFormSchema) implements Serializable {

    private static final long serialVersionUID = 1L;
}
