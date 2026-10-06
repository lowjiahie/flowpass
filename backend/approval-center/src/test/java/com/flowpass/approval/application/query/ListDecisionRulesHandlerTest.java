package com.flowpass.approval.application.query;

import com.flowpass.approval.contract.dto.DecisionRuleDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.decision.DecisionRule;
import com.flowpass.approval.domain.decision.DecisionRuleRepository;
import com.flowpass.approval.domain.decision.RuleCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ListDecisionRulesHandler} — template filter and DTO mapping. The port is mocked. */
@ExtendWith(MockitoExtension.class)
class ListDecisionRulesHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");

    @Mock
    private DecisionRuleRepository ruleRepository;

    private ListDecisionRulesHandler handler() {
        return new ListDecisionRulesHandler(ruleRepository);
    }

    private DecisionRule rule(String templateKey, int priority) {
        return DecisionRule.define(tenant, templateKey, priority,
                RuleCondition.amountBand(new BigDecimal("1000.00"), null),
                List.of(new ApproverRef("USER", "u_2")));
    }

    @Test
    void listsAllTenantRulesWhenTemplateKeyAbsent() {
        when(ruleRepository.findAll(tenant)).thenReturn(List.of(rule("purchase_request", 10)));

        List<DecisionRuleDTO> result = handler().handle(new ListDecisionRulesQuery(tenant, null));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).templateKey()).isEqualTo("purchase_request");
        assertThat(result.get(0).amountMin()).isEqualByComparingTo("1000.00");
        assertThat(result.get(0).approvers()).extracting("id").containsExactly("u_2");
        verify(ruleRepository).findAll(tenant);
    }

    @Test
    void filtersByTemplateWhenKeyPresent() {
        when(ruleRepository.findByTemplate(tenant, "purchase_request"))
                .thenReturn(List.of(rule("purchase_request", 10)));

        List<DecisionRuleDTO> result = handler().handle(new ListDecisionRulesQuery(tenant, "purchase_request"));

        assertThat(result).hasSize(1);
        verify(ruleRepository).findByTemplate(tenant, "purchase_request");
    }

    @Test
    void treatsBlankTemplateKeyAsAbsent() {
        when(ruleRepository.findAll(tenant)).thenReturn(List.of());

        assertThat(handler().handle(new ListDecisionRulesQuery(tenant, "  "))).isEmpty();

        verify(ruleRepository).findAll(tenant);
        verify(ruleRepository, never()).findByTemplate(any(), any());
    }
}
