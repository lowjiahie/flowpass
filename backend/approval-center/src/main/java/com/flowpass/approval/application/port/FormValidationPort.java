package com.flowpass.approval.application.port;

import com.flowpass.approval.application.common.FieldViolation;

import java.util.List;
import java.util.Map;

/**
 * Outbound port for template forms: vetting a form schema and checking an application's input against it.
 *
 * <p>The schema language (JSON Schema) and its engine are an infrastructure concern; the application only
 * handles the schema as opaque JSON text and the violations that come back. A schema is authored by a tenant,
 * so {@link #checkSchema} also refuses anything that could make validation unsafe or non-local.</p>
 */
public interface FormValidationPort {

    /**
     * Verify a schema is acceptable and return its canonical JSON text.
     *
     * @throws IllegalArgumentException with the reason when it is not a valid, supported, safe schema
     */
    String checkSchema(Map<String, Object> schema);

    /**
     * Check input variables against a stored schema.
     *
     * @param schemaJson canonical schema text previously returned by {@link #checkSchema}
     * @return every violation found, empty when the input is valid
     */
    List<FieldViolation> validate(String schemaJson, Map<String, Object> variables);
}
