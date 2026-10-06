package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Request to replace a template's draft process definition.
 *
 * @param bpmnXml BPMN 2.0 XML; must stay inside the platform flow contract (validated server-side)
 */
public record SaveTemplateDraftRequest(@NotBlank @Size(max = 262144) String bpmnXml) implements Serializable {

    private static final long serialVersionUID = 1L;
}
