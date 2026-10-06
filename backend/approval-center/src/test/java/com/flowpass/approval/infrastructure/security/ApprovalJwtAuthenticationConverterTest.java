package com.flowpass.approval.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalJwtAuthenticationConverterTest {

    private final ApprovalJwtAuthenticationConverter converter = new ApprovalJwtAuthenticationConverter();

    private Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none").subject("u-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
    }

    @Test
    void mapsApprovalApiClientRolesToAuthorities() {
        Jwt jwt = jwt().claim("resource_access", Map.of(
                "approval-api", Map.of("roles", List.of("approval.request.approve", "approval.request.read")),
                "marketing-api", Map.of("roles", List.of("marketing.campaign.publish")))).build();

        JwtAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("approval.request.approve", "approval.request.read");
        assertThat(token.getName()).isEqualTo("u-1");
    }

    @Test
    void ignoresRealmRolesAndOtherClients() {
        Jwt jwt = jwt().claim("realm_access", Map.of("roles", List.of("approval.request.approve")))
                .claim("resource_access", Map.of("marketing-api", Map.of("roles", List.of("x")))).build();

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    @Test
    void tokenWithoutResourceAccessHasNoAuthorities() {
        assertThat(converter.convert(jwt().claim("x", "y").build()).getAuthorities()).isEmpty();
    }
}
