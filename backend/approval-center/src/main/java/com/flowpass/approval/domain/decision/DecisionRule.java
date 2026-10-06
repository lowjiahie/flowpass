package com.flowpass.approval.domain.decision;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root in the {@code decision} bounded context.
 *
 * <p>A deterministic approval rule: when its {@link RuleCondition} matches an instance's variables, its
 * approvers become the first-round candidates. Rules are evaluated by ascending {@code priority} and the
 * first match wins (see {@link ApprovalMatrix}). Invariants: a rule always assigns at least one
 * approver, and a blank template key is rejected.</p>
 */
public final class DecisionRule {

    private final String id;
    private final TenantId tenantId;
    private final String templateKey;
    private final int priority;
    private final RuleCondition condition;
    private final List<ApproverRef> approvers;

    private DecisionRule(
            String id,
            TenantId tenantId,
            String templateKey,
            int priority,
            RuleCondition condition,
            List<ApproverRef> approvers) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("templateKey must not be blank");
        }
        this.templateKey = templateKey;
        this.priority = priority;
        this.condition = Objects.requireNonNull(condition);
        if (approvers == null || approvers.isEmpty()) {
            throw new IllegalArgumentException("a rule must assign at least one approver");
        }
        for (ApproverRef approver : approvers) {
            if (ApproverKind.parse(approver.type()).isEmpty()) {
                throw new IllegalArgumentException("unknown approver type '" + approver.type()
                        + "'; expected one of USER, ROLE, INITIATOR_MANAGER, DEPARTMENT_HEAD, FORM_FIELD");
            }
        }
        this.approvers = List.copyOf(approvers);
    }

    /** Define a new rule. */
    public static DecisionRule define(
            TenantId tenantId,
            String templateKey,
            int priority,
            RuleCondition condition,
            List<ApproverRef> approvers) {
        return new DecisionRule("rule_" + UUID.randomUUID(), tenantId, templateKey, priority, condition, approvers);
    }

    /** Rebuild an aggregate from a persistence row. */
    public static DecisionRule hydrate(
            String id,
            TenantId tenantId,
            String templateKey,
            int priority,
            RuleCondition condition,
            List<ApproverRef> approvers) {
        return new DecisionRule(id, tenantId, templateKey, priority, condition, approvers);
    }

    /** True when this rule's condition matches the given instance variables. */
    public boolean matches(Map<String, Object> variables) {
        return condition.matches(variables);
    }

    public String id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String templateKey() {
        return templateKey;
    }

    public int priority() {
        return priority;
    }

    public RuleCondition condition() {
        return condition;
    }

    public List<ApproverRef> approvers() {
        return approvers;
    }
}
