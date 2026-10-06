package com.flowpass.approval.infrastructure.config;

import com.flowpass.approval.infrastructure.security.ApprovalJwtAuthenticationConverter;
import com.flowpass.approval.infrastructure.security.DevActorFilter;
import com.flowpass.approval.infrastructure.security.ProblemDetailSecurityHandlers;
import com.flowpass.approval.infrastructure.tenant.JwtTenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Web security wiring — secure by default, two mutually-exclusive chains selected by profile.
 *
 * <ul>
 *   <li>default (any profile except {@code dev}): stateless JWT resource server. Every business endpoint
 *       requires a valid Keycloak access token; the tenant comes from the token's {@code tenant_id}
 *       claim ({@link JwtTenantContextFilter}) and the actor from its subject. Actuator/docs stay open
 *       for ops. Requires {@code spring.security.oauth2.resourceserver.jwt.*} to be configured.</li>
 *   <li>{@code dev}: explicit local-only shortcut — the actor comes from {@code X-Actor-*} headers
 *       ({@link DevActorFilter}) and the tenant from {@code X-Tenant-Id}. Endpoints still require an
 *       authenticated actor, so 401/403 paths behave like production.</li>
 * </ul>
 *
 * <p>Method security is enabled for both: the application handlers carry {@code @PreAuthorize}, which is
 * the real authorization boundary (charter §5).</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] OPEN_PATHS =
            {"/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    @Bean
    @Profile("dev")
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(OPEN_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(ProblemDetailSecurityHandlers.ENTRY_POINT)
                        .accessDeniedHandler(ProblemDetailSecurityHandlers.ACCESS_DENIED))
                .addFilterBefore(new DevActorFilter(), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Profile("!dev")
    public SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(OPEN_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(ProblemDetailSecurityHandlers.ENTRY_POINT)
                        .accessDeniedHandler(ProblemDetailSecurityHandlers.ACCESS_DENIED))
                .oauth2ResourceServer(rs -> rs
                        .authenticationEntryPoint(ProblemDetailSecurityHandlers.ENTRY_POINT)
                        .accessDeniedHandler(ProblemDetailSecurityHandlers.ACCESS_DENIED)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ApprovalJwtAuthenticationConverter())))
                .addFilterAfter(new JwtTenantContextFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
