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
}
