package com.flowpass.approval.support;

import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.test.context.support.WithMockUser;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test security context holding every approval authority, so tests that drive the {@code @PreAuthorize}
 * guarded handlers directly (without HTTP) pass the technical-permission check. Resource-level rules
 * (assignee / initiator) are still enforced by the commands' actor.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@WithMockUser(username = "test-admin", authorities = {
        Permissions.REQUEST_CREATE, Permissions.REQUEST_READ, Permissions.REQUEST_APPROVE,
        Permissions.REQUEST_REJECT, Permissions.REQUEST_AUDIT, Permissions.TEMPLATE_MANAGE,
        Permissions.RULE_MANAGE, Permissions.WEBHOOK_MANAGE})
public @interface WithAllApprovalAuthorities {
}
