package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;
import java.util.Map;

/**
 * Request to replace a template's draft form schema.
 *
 * @param formSchema a JSON Schema object (2020-12, or draft-07 when declared) describing the application's input
 *                   variables; validated server-side — self-contained, no regular expressions, bounded size
 */
public record SaveTemplateFormSchemaRequest(@NotEmpty Map<String, Object> formSchema) implements Serializable {

    private static final long serialVersionUID = 1L;
}
