package com.flowpass.approval.application.common;

/**
 * Authority names guarding the application handlers (used in {@code @PreAuthorize}).
 *
 * <p>The request/audit names are the static Keycloak client roles of the {@code approval-api} audience
 * (see saas-iam-authorization-architecture.md §4.4). The template / rule / webhook management roles are
 * approval-service extensions of that list. Resource-level rules — who is the assignee, who is the
 * initiator — are <em>not</em> expressed here; they live in the domain
 * ({@code TaskAuthorizationPolicy}, {@code ApprovalInstance#isInitiatedBy}).</p>
 */
public final class Permissions {

    public static final String REQUEST_CREATE = "approval.request.create";
    public static final String REQUEST_READ = "approval.request.read";
    public static final String REQUEST_APPROVE = "approval.request.approve";
    public static final String REQUEST_REJECT = "approval.request.reject";
    public static final String REQUEST_AUDIT = "approval.request.audit";
    public static final String TEMPLATE_MANAGE = "approval.template.manage";
    public static final String RULE_MANAGE = "approval.rule.manage";
    public static final String WEBHOOK_MANAGE = "approval.webhook.manage";

    /** Every authority above — granted to the local {@code dev} identity only. */
    public static final java.util.List<String> ALL = java.util.List.of(
            REQUEST_CREATE, REQUEST_READ, REQUEST_APPROVE, REQUEST_REJECT, REQUEST_AUDIT,
            TEMPLATE_MANAGE, RULE_MANAGE, WEBHOOK_MANAGE);

    private Permissions() {
    }
}
