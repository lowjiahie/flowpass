package com.flowpass.approval.interfaces.web;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates application-level business exceptions into a stable, predictable JSON error body.
 *
 * <p>Only expected business errors are surfaced with a code; unexpected failures are logged and
 * returned as a generic UNKNOWN error — never the raw exception class, SQL or stack trace (charter 9).</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusiness(BusinessException ex) {
        HttpStatus status = switch (ex.code()) {
            case INSTANCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(Map.of("code", ex.code().code(), "message", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", BusinessErrorCode.UNKNOWN.code(), "message", "Internal error"));
    }
}
