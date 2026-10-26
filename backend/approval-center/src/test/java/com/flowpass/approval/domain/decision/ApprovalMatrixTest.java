package com.flowpass.approval.domain.decision;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Domain tests for the deterministic {@link ApprovalMatrix} selection (first match by priority wins). */
class ApprovalMatrixTest {

    private final TenantId tenant = new TenantId("t_10086");

    private DecisionRule bandedRule(int priority, BigDecimal min, BigDecimal max, String approverId) {
        return DecisionRule.define(tenant, "purchase_request", priority,
                RuleCondition.amountBand(min, max), List.of(new ApproverRef("USER", approverId)));
    }

    @Test
    void firstMatchWinsByAscendingPriority() {
        DecisionRule later = bandedRule(20, new BigDecimal("1000"), null, "u_later");
        DecisionRule earlier = bandedRule(10, new BigDecimal("1000"), null, "u_earlier");

        DecisionRule selected = ApprovalMatrix.select(List.of(later, earlier), Map.of("amount", 5000)).orElseThrow();

        assertThat(selected.approvers().get(0).id()).isEqualTo("u_earlier");
    }

    @Test
    void skipsNonMatchingHigherPriorityRule() {
        DecisionRule small = bandedRule(10, null, new BigDecimal("1000"), "u_small");
        DecisionRule large = bandedRule(20, new BigDecimal("1000"), null, "u_large");

        DecisionRule selected = ApprovalMatrix.select(List.of(small, large), Map.of("amount", 5000)).orElseThrow();

        assertThat(selected.approvers().get(0).id()).isEqualTo("u_large");
    }

    @Test
    void noMatchYieldsEmpty() {
        assertThat(ApprovalMatrix.select(List.of(bandedRule(10, new BigDecimal("10000"), null, "u_x")),
                Map.of("amount", 5))).isEmpty();
    }

    @Test
    void emptyRulesYieldEmpty() {
        assertThat(ApprovalMatrix.select(List.of(), Map.of())).isEmpty();
    }

    @Test
    void expressionsDecideWhichRuleWinsInPriorityOrder() {
        DecisionRule euUrgent = DecisionRule.define(new TenantId("t_1"), "purchase_request", 10,
                new RuleCondition(null, null, null, RuleExpression.parse("region == 'EU' && urgent")),
                List.of(new ApproverRef("USER", "u_eu")));
        DecisionRule big = DecisionRule.define(new TenantId("t_1"), "purchase_request", 20,
                new RuleCondition(null, null, null, RuleExpression.parse("amount >= 10000")),
                List.of(new ApproverRef("USER", "u_big")));
        DecisionRule fallback = DecisionRule.define(new TenantId("t_1"), "purchase_request", 100,
                RuleCondition.any(), List.of(new ApproverRef("USER", "u_default")));
        List<DecisionRule> rules = List.of(fallback, big, euUrgent);

        assertThat(ApprovalMatrix.select(rules, Map.of("region", "EU", "urgent", true, "amount", 50000)).orElseThrow()
                .approvers()).containsExactly(new ApproverRef("USER", "u_eu"));
        assertThat(ApprovalMatrix.select(rules, Map.of("region", "EU", "urgent", false, "amount", 50000)).orElseThrow()
                .approvers()).containsExactly(new ApproverRef("USER", "u_big"));
        assertThat(ApprovalMatrix.select(rules, Map.of("amount", 5)).orElseThrow()
                .approvers()).containsExactly(new ApproverRef("USER", "u_default"));
    }
}
