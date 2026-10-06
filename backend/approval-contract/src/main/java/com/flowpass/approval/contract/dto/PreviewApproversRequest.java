package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.util.Map;

/**
 * Request to preview which approvers the approval matrix would resolve for a set of variables — a dry
 * run of the same resolution the start use case performs, so a UI can show the outcome before
 * submitting.
 *
 * @param templateKey template whose rules are evaluated (required)
 * @param variables   instance variables to evaluate (e.g. {@code amount}); may be omitted
 * @param initiatorId the user the request would be started by; manager / department entries resolve
 *                    relative to them. Defaults to the caller when omitted.
 */
public record PreviewApproversRequest(
        @NotBlank @Size(max = 64) String templateKey,
        Map<String, Object> variables,
        @Size(max = 128) String initiatorId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
