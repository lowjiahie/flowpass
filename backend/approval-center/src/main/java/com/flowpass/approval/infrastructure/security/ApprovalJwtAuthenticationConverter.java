package com.flowpass.approval.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps a verified Keycloak access token to Spring authorities.
 *
 * <p>Authorities are the static client roles of the {@code approval-api} audience
 * ({@code resource_access.approval-api.roles}) used verbatim — e.g. {@code approval.request.approve}.
 * Realm roles and other clients' roles are deliberately ignored (the token must carry approval
 * permissions only; see saas-iam-authorization-architecture.md §4.4).</p>
 */
public class ApprovalJwtAuthenticationConverter implements Converter<Jwt, JwtAuthenticationToken> {

    static final String CLIENT = "approval-api";

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        Object resourceAccess = jwt.getClaim("resource_access");
        if (!(resourceAccess instanceof Map<?, ?> clients) || !(clients.get(CLIENT) instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority((String) role))
                .toList();
    }
}
