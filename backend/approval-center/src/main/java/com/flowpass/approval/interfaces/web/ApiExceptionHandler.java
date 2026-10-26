package com.flowpass.approval.interfaces.web;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates application-level business exceptions into an RFC 7807 problem detail body
 * ({@code application/problem+json}).
 *
 * <p>The HTTP status carries the semantics and the stable business code travels in the {@code code}
 * property, so clients switch on either. Unexpected failures are logged and returned as a generic
 * UNKNOWN problem — never the raw exception class, SQL or stack trace (charter 9).</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex) {
        HttpStatus status = switch (ex.code()) {
            case INSTANCE_NOT_FOUND, TASK_NOT_FOUND, TEMPLATE_NOT_FOUND, DELEGATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail problem = problem(status, ex.code(), ex.getMessage());
        if (!ex.errors().isEmpty()) {
            // Every offending field at once, so a client can show them all instead of one per round trip.
            problem.setProperty("errors", ex.errors().stream()
                    .map(error -> Map.of("field", error.field(), "message", error.message()))
                    .toList());
        }
        return problem;
    }

    /** Thrown by {@code @PreAuthorize} on a handler — the caller lacks the required authority. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, BusinessErrorCode.FORBIDDEN, "Access denied");
    }

    /** No / invalid authentication reached a secured handler (includes a missing security context). */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, BusinessErrorCode.UNAUTHENTICATED, "Authentication required");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        // Summarise the field errors only — the framework message leaks controller signatures.
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, BusinessErrorCode.VALIDATION_FAILED,
                detail.isBlank() ? "Validation failed" : detail);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, BusinessErrorCode.UNKNOWN, "Internal error");
    }

    private ProblemDetail problem(HttpStatus status, BusinessErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code.code());
        return problem;
    }
}
