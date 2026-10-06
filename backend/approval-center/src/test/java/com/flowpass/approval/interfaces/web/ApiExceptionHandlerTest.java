package com.flowpass.approval.interfaces.web;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the error contract: business errors map to a semantic HTTP status plus a stable
 * {@code code} property, and unexpected failures never leak their message.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsNotFoundBusinessErrorTo404() {
        ProblemDetail problem = handler.handleBusiness(
                new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND, "Template not found: x"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Not Found");
        assertThat(problem.getDetail()).isEqualTo("Template not found: x");
        assertThat(problem.getProperties()).containsEntry("code", "approval.template.not_found");
    }

    @Test
    void mapsConflictBusinessErrorTo409() {
        ProblemDetail problem = handler.handleBusiness(
                new BusinessException(BusinessErrorCode.IDEMPOTENCY_CONFLICT, "duplicate key"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getProperties()).containsEntry("code", "approval.idempotency_conflict");
    }

    @Test
    void mapsStateBusinessErrorTo400() {
        ProblemDetail problem = handler.handleBusiness(
                new BusinessException(BusinessErrorCode.ILLEGAL_STATE, "Cannot publish"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("code", "approval.illegal_state");
    }

    @Test
    void mapsForbiddenAndUnauthenticatedBusinessErrors() {
        ProblemDetail forbidden = handler.handleBusiness(
                new BusinessException(BusinessErrorCode.FORBIDDEN, "Not the assignee"));
        assertThat(forbidden.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(forbidden.getProperties()).containsEntry("code", "approval.forbidden");

        ProblemDetail unauthenticated = handler.handleBusiness(
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "Authentication required"));
        assertThat(unauthenticated.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(unauthenticated.getProperties()).containsEntry("code", "approval.unauthenticated");
    }

    @Test
    void mapsSpringSecurityExceptionsWithoutLeakingTheirMessage() {
        ProblemDetail denied = handler.handleAccessDenied(
                new org.springframework.security.access.AccessDeniedException("secret authority expression"));
        assertThat(denied.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(denied.getDetail()).doesNotContain("secret");
        assertThat(denied.getProperties()).containsEntry("code", "approval.forbidden");

        ProblemDetail unauthenticated = handler.handleAuthentication(
                new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("ctx"));
        assertThat(unauthenticated.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(unauthenticated.getProperties()).containsEntry("code", "approval.unauthenticated");
    }

    @Test
    void summarisesFieldErrorsWithoutLeakingFrameworkMessage() throws Exception {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "createTemplateRequest");
        binding.addError(new FieldError("createTemplateRequest", "templateKey", "must not be blank"));
        MethodParameter parameter = new MethodParameter(
                getClass().getDeclaredMethod("sampleEndpoint", String.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, binding);

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("templateKey must not be blank");
        assertThat(problem.getProperties()).containsEntry("code", "approval.validation_failed");
    }

    @SuppressWarnings("unused")
    private void sampleEndpoint(String templateKey) {
    }

    @Test
    void hidesUnexpectedFailureDetails() {
        ProblemDetail problem = handler.handleUnexpected(new IllegalStateException("secret sql text"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).isEqualTo("Internal error");
        assertThat(problem.getDetail()).doesNotContain("secret");
        assertThat(problem.getProperties()).containsEntry("code", "approval.unknown");
    }
}
