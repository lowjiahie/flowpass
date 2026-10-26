package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Command to define a new approval-matrix rule.
 *
 * <p>Carries the structured condition (amount band, field matches and/or a condition expression) and the approvers to assign
 * when the rule matches. {@code rounds} runs parallel to {@code approvers}: the approval round (1 to 5) of each
 * one; {@code null} (or a null entry) means round 1. The tenant id is trusted (resolved server-side).</p>
 */
public record CreateDecisionRuleCommand(
        TenantId tenantId,
        String templateKey,
        int priority,
        BigDecimal amountMin,
        BigDecimal amountMax,
        Map<String, String> fieldMatches,
        String expression,
        List<ApproverRef> approvers,
        List<Integer> rounds) {

    /** A rule without a condition expression (amount band and / or field matches only). */
    public CreateDecisionRuleCommand(TenantId tenantId, String templateKey, int priority, BigDecimal amountMin,
                                     BigDecimal amountMax, Map<String, String> fieldMatches,
                                     List<ApproverRef> approvers) {
        this(tenantId, templateKey, priority, amountMin, amountMax, fieldMatches, null, approvers, null);
    }

    /** A rule with a condition expression and a single approval round. */
    public CreateDecisionRuleCommand(TenantId tenantId, String templateKey, int priority, BigDecimal amountMin,
                                     BigDecimal amountMax, Map<String, String> fieldMatches, String expression,
                                     List<ApproverRef> approvers) {
        this(tenantId, templateKey, priority, amountMin, amountMax, fieldMatches, expression, approvers, null);
    }
}
