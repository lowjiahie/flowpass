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
 * @param initiatorType     optional; with {@code initiatorId}, the principal to start on behalf of.
 *                          Honoured only for a {@code SYSTEM} caller — a user always starts as themselves
 * @param initiatorId       see {@code initiatorType}
 * @param variables         form / rule variables snapshot
 * @param callbackContext   opaque context echoed back on webhook callbacks
 */
public record CreateApprovalRequest(
        @NotBlank @Size(max = 64) String templateKey,
        @NotBlank @Size(max = 128) String externalBusinessKey,
        String initiatorType,
        String initiatorId,
        @NotNull Map<String, Object> variables,
        Map<String, Object> callbackContext) implements Serializable {

    private static final long serialVersionUID = 1L;
}
