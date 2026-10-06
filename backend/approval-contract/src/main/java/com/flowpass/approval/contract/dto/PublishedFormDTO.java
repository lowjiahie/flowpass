package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * The form an applicant fills in for a template's latest published version.
 *
 * @param templateKey the template
 * @param version     the published version this form belongs to — the one a new application will pin
 * @param formSchema  the JSON Schema as JSON text, or null when the form is unconstrained
 */
public record PublishedFormDTO(String templateKey, int version, String formSchema) implements Serializable {

    private static final long serialVersionUID = 1L;
}
