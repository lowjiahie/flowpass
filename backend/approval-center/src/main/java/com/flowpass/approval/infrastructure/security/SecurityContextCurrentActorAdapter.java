package com.flowpass.approval.infrastructure.security;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.CurrentActorPort;
import com.flowpass.approval.domain.common.ApproverRef;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * {@link CurrentActorPort} backed by the Spring Security context.
 *
 * <p>Under the JWT chain the actor is derived from the signature-verified token: {@code sub} is the id and
 * a Keycloak service-account token ({@code preferred_username} = {@code service-account-*}) is a
 * {@code SYSTEM} actor, everything else a {@code USER}. Under the {@code dev} chain the principal is
 * already an {@link ApproverRef} (see {@link DevActorFilter}). Nothing is read from the request body.</p>
 */
@Component
public class SecurityContextCurrentActorAdapter implements CurrentActorPort {

    static final String USER = "USER";
    static final String SYSTEM = "SYSTEM";
    private static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    @Override
    public ApproverRef currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (authentication.getPrincipal() instanceof ApproverRef actor) {
            return actor;
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String subject = jwt.getToken().getSubject();
            if (subject == null || subject.isBlank()) {
                throw new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "Token has no subject");
            }
            String username = jwt.getToken().getClaimAsString("preferred_username");
            boolean serviceAccount = username != null && username.startsWith(SERVICE_ACCOUNT_PREFIX);
            return new ApproverRef(serviceAccount ? SYSTEM : USER, subject);
        }
        throw new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "Unsupported authentication");
    }
}
