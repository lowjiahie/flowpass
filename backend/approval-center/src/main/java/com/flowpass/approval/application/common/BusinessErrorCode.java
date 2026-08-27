package com.flowpass.approval.application.common;

/**
 * Stable, machine-readable business error codes shared across the contract.
 *
 * <p>Errors surfaced to callers must always be one of these codes — never a raw exception class
 * name, SQL message, or stack trace (per charter section 9).</p>
 */
public enum BusinessErrorCode {

    INSTANCE_NOT_FOUND("approval.instance.not_found"),
    VALIDATION_FAILED("approval.validation_failed"),
    ILLEGAL_STATE("approval.illegal_state"),
    CONFLICT("approval.conflict"),
    UNKNOWN("approval.unknown");

    private final String code;

    BusinessErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
