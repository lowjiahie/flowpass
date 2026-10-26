package com.flowpass.approval.domain.decision;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain tests for the {@link DecisionRule} aggregate invariants. */
class DecisionRuleTest {

    private final TenantId tenant = new TenantId("t_10086");

    @Test
    void defineRequiresAtLeastOneApprover() {
        assertThatThrownBy(() -> DecisionRule.define(tenant, "purchase_request", 10, RuleCondition.any(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defineRejectsBlankTemplateKey() {
        assertThatThrownBy(() -> DecisionRule.define(
                tenant, "  ", 10, RuleCondition.any(), List.of(new ApproverRef("USER", "u_2"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsApproversAndDelegatesMatchToCondition() {
        DecisionRule rule = DecisionRule.define(tenant, "purchase_request", 10, RuleCondition.any(),
                List.of(new ApproverRef("USER", "u_2"), new ApproverRef("USER", "u_3")));

        assertThat(rule.id()).startsWith("rule_");
        assertThat(rule.templateKey()).isEqualTo("purchase_request");
        assertThat(rule.priority()).isEqualTo(10);
        assertThat(rule.approvers()).hasSize(2);
        assertThat(rule.matches(Map.of())).isTrue();
    }

    // ---- rounds ----------------------------------------------------------------------------------------

    @Test
    void aRuleCanHoldAnApprovalChainOfSeveralRounds() {
        DecisionRule rule = DecisionRule.defineRounds(new TenantId("t_1"), "purchase_request", 10, RuleCondition.any(),
                List.of(List.of(new ApproverRef("USER", "u_a")),
                        List.of(new ApproverRef("ROLE", "finance"), new ApproverRef("USER", "u_b"))));

        assertThat(rule.rounds()).hasSize(2);
        assertThat(rule.approvers()).extracting(ApproverRef::id).containsExactly("u_a", "finance", "u_b");
    }

    @Test
    void aSingleListIsOneRound() {
        DecisionRule rule = DecisionRule.define(new TenantId("t_1"), "purchase_request", 10, RuleCondition.any(),
                List.of(new ApproverRef("USER", "u_a"), new ApproverRef("USER", "u_b")));

        assertThat(rule.rounds()).hasSize(1);
        assertThat(rule.rounds().get(0)).hasSize(2);
    }

    @Test
    void emptyRoundsAreDroppedButAtLeastOneApproverIsRequired() {
        DecisionRule rule = DecisionRule.defineRounds(new TenantId("t_1"), "purchase_request", 10, RuleCondition.any(),
                List.of(List.of(), List.of(new ApproverRef("USER", "u_a"))));
        assertThat(rule.rounds()).hasSize(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DecisionRule.defineRounds(new TenantId("t_1"),
                        "purchase_request", 10, RuleCondition.any(), List.of(List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one approver");
    }

    @Test
    void theNumberOfRoundsAndApproversPerRoundIsBounded() {
        List<List<ApproverRef>> tooManyRounds = java.util.stream.IntStream.rangeClosed(1, DecisionRule.MAX_ROUNDS + 1)
                .mapToObj(i -> List.of(new ApproverRef("USER", "u_" + i))).toList();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DecisionRule.defineRounds(new TenantId("t_1"),
                        "purchase_request", 10, RuleCondition.any(), tooManyRounds))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 5");

        List<ApproverRef> crowd = java.util.stream.IntStream.rangeClosed(1, DecisionRule.MAX_APPROVERS_PER_ROUND + 1)
                .mapToObj(i -> new ApproverRef("USER", "u_" + i)).toList();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DecisionRule.define(new TenantId("t_1"),
                        "purchase_request", 10, RuleCondition.any(), crowd))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 20");
    }
}
