package com.flowpass.approval.application.common;

import java.util.List;

/**
 * Application-level business exception mapped to a stable error code.
 *
 * <p>Thrown by use cases for expected business failures (e.g. aggregate not found, illegal state)
 * and translated by the interface layer into a predictable JSON body. Never use this for unexpected
 * internal errors — those should surface as generic failures and be logged, not returned verbatim.</p>
 *
 * <p>A validation failure may carry per-field {@link #errors()} so a caller can show every problem at once.</p>
 */
public class BusinessException extends RuntimeException {

    private final BusinessErrorCode code;
    private final List<FieldViolation> errors;

    public BusinessException(BusinessErrorCode code, String message) {
        this(code, message, List.of());
    }

    public BusinessException(BusinessErrorCode code, String message, List<FieldViolation> errors) {
        super(message);
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public BusinessErrorCode code() {
        return code;
    }

    /** Per-field problems (empty when the failure is not about specific input fields). */
    public List<FieldViolation> errors() {
        return errors;
    }
}
