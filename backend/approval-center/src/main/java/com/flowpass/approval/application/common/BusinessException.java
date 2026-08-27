package com.flowpass.approval.application.common;

/**
 * Application-level business exception mapped to a stable error code.
 *
 * <p>Thrown by use cases for expected business failures (e.g. aggregate not found, illegal state)
 * and translated by the interface layer into a predictable JSON body. Never use this for unexpected
 * internal errors — those should surface as generic failures and be logged, not returned verbatim.</p>
 */
public class BusinessException extends RuntimeException {

    private final BusinessErrorCode code;

    public BusinessException(BusinessErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessErrorCode code() {
        return code;
    }
}
