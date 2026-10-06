package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * A published version together with its process definition (BPMN XML) and form schema.
 *
 * @param formSchema the form schema published with this version as JSON text, or null when it had none
 */
public record TemplateVersionDetailDTO(int version, String checksum, String bpmnXml, String formSchema)
        implements Serializable {

    private static final long serialVersionUID = 1L;
}
