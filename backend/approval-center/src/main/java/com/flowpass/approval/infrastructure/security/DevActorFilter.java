package com.flowpass.approval.infrastructure.security;

import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.domain.common.ApproverRef;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Local-development identity: builds the authenticated actor from request headers.
 *
 * <p>TODO(security): the pre-Keycloak temporary adapter — only wired under the {@code dev} profile
 * (see {@code SecurityConfig}, guarded by {@code DevProfileGuard}); it trusts the headers as-is and must
 * never run in a shared environment. A request without {@code X-Actor-Id} stays unauthenticated (401).</p>
 *
 * <ul>
 *   <li>{@code X-Actor-Id}: the actor id (required);</li>
 *   <li>{@code X-Actor-Type}: {@code USER} (default) or {@code SYSTEM};</li>
 *   <li>{@code X-Actor-Authorities}: optional comma-separated authorities; default is every approval
 *       authority — pass a subset to exercise 403 paths manually.</li>
 * </ul>
 */
public class DevActorFilter extends OncePerRequestFilter {

    static final String ID_HEADER = "X-Actor-Id";
    static final String TYPE_HEADER = "X-Actor-Type";
    static final String AUTHORITIES_HEADER = "X-Actor-Authorities";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(ID_HEADER);
        if (id != null && !id.isBlank()) {
            String type = request.getHeader(TYPE_HEADER);
            ApproverRef actor = new ApproverRef(type == null || type.isBlank() ? "USER" : type.trim(), id.trim());
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(actor, null, authorities(request)));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private List<SimpleGrantedAuthority> authorities(HttpServletRequest request) {
        String header = request.getHeader(AUTHORITIES_HEADER);
        List<String> names = (header == null || header.isBlank())
                ? Permissions.ALL
                : Arrays.stream(header.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return names.stream().map(SimpleGrantedAuthority::new).toList();
    }
}
