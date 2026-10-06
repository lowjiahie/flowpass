package com.flowpass.approval.application.command;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Command to define a new approval-matrix rule.
 *
 * <p>Carries the structured condition (amount band and/or field matches) and the approvers to assign
 * when the rule matches. The tenant id is trusted (resolved server-side).</p>
 */
public record CreateDecisionRuleCommand(
        TenantId tenantId,
        String templateKey,
        int priority,
        BigDecimal amountMin,
        BigDecimal amountMax,
        Map<String, String> fieldMatches,
        List<ApproverRef> approvers) {
}
