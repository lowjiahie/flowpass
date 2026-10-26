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
 * approvers become the approval chain. The chain is made of <em>rounds</em> (1 to {@link #MAX_ROUNDS}); the
 * approvers of a round act in parallel and every one of them must approve, and the next round only opens once
 * the previous one is done. A rule with a single round is the plain "these people approve" case. Rules are
 * evaluated by ascending {@code priority} and the first match wins (see {@link ApprovalMatrix}).</p>
 *
 * <p>Invariants: at least one approver, every approver of a known {@link ApproverKind}, at most
 * {@link #MAX_ROUNDS} rounds with at most {@link #MAX_APPROVERS_PER_ROUND} approvers each, and a non-blank
 * template key.</p>
 */
public final class DecisionRule {

    public static final int MAX_ROUNDS = 5;
    public static final int MAX_APPROVERS_PER_ROUND = 20;

    private final String id;
    private final TenantId tenantId;
    private final String templateKey;
    private final int priority;
    private final RuleCondition condition;
    private final List<List<ApproverRef>> rounds;

    private DecisionRule(
            String id,
            TenantId tenantId,
            String templateKey,
            int priority,
            RuleCondition condition,
            List<List<ApproverRef>> rounds) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("templateKey must not be blank");
        }
        this.templateKey = templateKey;
        this.priority = priority;
        this.condition = Objects.requireNonNull(condition);
        if (rounds == null || rounds.isEmpty() || rounds.stream().allMatch(List::isEmpty)) {
            throw new IllegalArgumentException("a rule must assign at least one approver");
        }
        List<List<ApproverRef>> kept = rounds.stream().filter(round -> !round.isEmpty()).toList();
        if (kept.size() > MAX_ROUNDS) {
            throw new IllegalArgumentException("a rule can have at most " + MAX_ROUNDS + " approval rounds");
        }
        for (List<ApproverRef> round : kept) {
            if (round.size() > MAX_APPROVERS_PER_ROUND) {
                throw new IllegalArgumentException("an approval round can have at most " + MAX_APPROVERS_PER_ROUND
                        + " approvers");
            }
            for (ApproverRef approver : round) {
                if (ApproverKind.parse(approver.type()).isEmpty()) {
                    throw new IllegalArgumentException("unknown approver type '" + approver.type()
                            + "'; expected one of USER, ROLE, INITIATOR_MANAGER, DEPARTMENT_HEAD, FORM_FIELD");
                }
            }
        }
        this.rounds = kept.stream().map(List::copyOf).toList();
    }

    /** Define a new rule with a single approval round. */
    public static DecisionRule define(
            TenantId tenantId,
            String templateKey,
            int priority,
            RuleCondition condition,
            List<ApproverRef> approvers) {
        return new DecisionRule("rule_" + UUID.randomUUID(), tenantId, templateKey, priority, condition,
                approvers == null ? null : List.of(approvers));
    }

    /** Define a new rule with an approval chain: the approvers of round 1, then round 2, and so on. */
    public static DecisionRule defineRounds(
            TenantId tenantId,
            String templateKey,
            int priority,
            RuleCondition condition,
            List<List<ApproverRef>> rounds) {
        return new DecisionRule("rule_" + UUID.randomUUID(), tenantId, templateKey, priority, condition, rounds);
    }

    /** Rebuild an aggregate (single round) from a persistence row. */
    public static DecisionRule hydrate(
            String id,
            TenantId tenantId,
            String templateKey,
            int priority,
            RuleCondition condition,
            List<ApproverRef> approvers) {
        return new DecisionRule(id, tenantId, templateKey, priority, condition,
                approvers == null ? null : List.of(approvers));
    }

    /** Rebuild an aggregate with several rounds from a persistence row. */
    public static DecisionRule hydrateRounds(
            String id,
            TenantId tenantId,
            String templateKey,
            int priority,
            RuleCondition condition,
            List<List<ApproverRef>> rounds) {
        return new DecisionRule(id, tenantId, templateKey, priority, condition, rounds);
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

    /** The approval chain, round by round (never empty; no empty round). */
    public List<List<ApproverRef>> rounds() {
        return rounds;
    }

    /** Every approver of every round, in order — the plain list for single-round rules. */
    public List<ApproverRef> approvers() {
        return rounds.stream().flatMap(List::stream).toList();
    }
}
