package com.flowpass.approval.infrastructure.security;

import com.flowpass.approval.application.common.BusinessErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Renders filter-chain level 401/403 as RFC 7807 {@code application/problem+json} with the stable
 * business code, so security failures look like every other API error (charter §8).
 */
public final class ProblemDetailSecurityHandlers {

    public static final AuthenticationEntryPoint ENTRY_POINT = (request, response, ex) ->
            write(response, HttpStatus.UNAUTHORIZED, BusinessErrorCode.UNAUTHENTICATED, "Authentication required");

    public static final AccessDeniedHandler ACCESS_DENIED = (request, response, ex) ->
            write(response, HttpStatus.FORBIDDEN, BusinessErrorCode.FORBIDDEN, "Access denied");

    private ProblemDetailSecurityHandlers() {
    }

    private static void write(HttpServletResponse response, HttpStatus status, BusinessErrorCode code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Constants only — no request data is echoed back.
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail
                + "\",\"code\":\"" + code.code() + "\"}");
    }
}
