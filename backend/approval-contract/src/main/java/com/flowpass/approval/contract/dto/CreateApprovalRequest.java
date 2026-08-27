package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.util.Map;

/**
 * Request to start a new approval instance — the public entry point for external business systems.
 *
 * <p>Mirrors the Phase-1 API contract (POST /v1/approval-instances). Immutable and serializable;
 * deliberately free of domain/framework types.</p>
 *
 * @param templateKey       published template key to run against (required)
 * @param externalBusinessKey unique business reference from the caller, idempotency anchor (required)
 * @param initiatorType     {@code USER} or {@code SYSTEM} (required)
 * @param initiatorId       caller-supplied actor id (required)
 * @param variables         form / rule variables snapshot
 * @param callbackContext   opaque context echoed back on webhook callbacks
 */
public record CreateApprovalRequest(
        @NotBlank @Size(max = 64) String templateKey,
        @NotBlank @Size(max = 128) String externalBusinessKey,
        @NotBlank String initiatorType,
        @NotBlank String initiatorId,
        @NotNull Map<String, Object> variables,
        Map<String, Object> callbackContext) implements Serializable {

    private static final long serialVersionUID = 1L;
}
