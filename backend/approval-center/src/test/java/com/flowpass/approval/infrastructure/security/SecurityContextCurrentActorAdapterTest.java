package com.flowpass.approval.infrastructure.security;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.domain.common.ApproverRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityContextCurrentActorAdapterTest {

    private final SecurityContextCurrentActorAdapter adapter = new SecurityContextCurrentActorAdapter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
    }

    @Test
    void userTokenYieldsUserActorFromSubject() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt().subject("kc-user-1").claim("preferred_username", "alice").build(), List.of()));

        assertThat(adapter.currentActor()).isEqualTo(new ApproverRef("USER", "kc-user-1"));
    }

    @Test
    void serviceAccountTokenYieldsSystemActor() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt().subject("kc-svc-1").claim("preferred_username", "service-account-erp").build(), List.of()));

        assertThat(adapter.currentActor()).isEqualTo(new ApproverRef("SYSTEM", "kc-svc-1"));
    }

    @Test
    void devPrincipalIsReturnedAsIs() {
        ApproverRef dev = new ApproverRef("USER", "u_2");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(dev, null, List.of()));

        assertThat(adapter.currentActor()).isEqualTo(dev);
    }

    @Test
    void noAuthenticationIsUnauthenticated() {
        assertThatThrownBy(adapter::currentActor)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.UNAUTHENTICATED));
    }

    @Test
    void anonymousIsUnauthenticated() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(adapter::currentActor).isInstanceOf(BusinessException.class);
    }

    @Test
    void tokenWithoutSubjectIsUnauthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt().claim("x", "y").build(), List.of()));

        assertThatThrownBy(adapter::currentActor).isInstanceOf(BusinessException.class);
    }
}
