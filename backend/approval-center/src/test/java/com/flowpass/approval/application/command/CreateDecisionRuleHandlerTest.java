package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.contract.dto.DecisionRuleDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.decision.DecisionRule;
import com.flowpass.approval.domain.decision.DecisionRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/** Use-case tests for {@link CreateDecisionRuleHandler} — happy path plus domain-invariant rejection. */
@ExtendWith(MockitoExtension.class)
class CreateDecisionRuleHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");

    @Mock
    private DecisionRuleRepository ruleRepository;

    private CreateDecisionRuleHandler handler() {
        return new CreateDecisionRuleHandler(ruleRepository);
    }

    @Test
    void createsRuleAndMapsToDto() {
        CreateDecisionRuleCommand cmd = new CreateDecisionRuleCommand(
                tenant,
                "purchase_request",
                10,
                new BigDecimal("1000.00"),
                null,
                Map.of("currency", "CNY"),
                List.of(new ApproverRef("USER", "u_2"), new ApproverRef("USER", "u_3")));

        DecisionRuleDTO dto = handler().handle(cmd);

        assertThat(dto.id()).startsWith("rule_");
        assertThat(dto.templateKey()).isEqualTo("purchase_request");
        assertThat(dto.priority()).isEqualTo(10);
        assertThat(dto.amountMin()).isEqualByComparingTo("1000.00");
        assertThat(dto.amountMax()).isNull();
        assertThat(dto.fieldMatches()).containsEntry("currency", "CNY");
        assertThat(dto.approvers()).extracting("id").containsExactly("u_2", "u_3");

        ArgumentCaptor<DecisionRule> captor = ArgumentCaptor.forClass(DecisionRule.class);
        verify(ruleRepository).save(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(tenant);
        assertThat(captor.getValue().approvers()).hasSize(2);
    }

    @Test
    void rejectsEmptyApprovers() {
        CreateDecisionRuleCommand cmd = new CreateDecisionRuleCommand(
                tenant, "purchase_request", 10, null, null, null, List.of());

        assertThatThrownBy(() -> handler().handle(cmd))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.VALIDATION_FAILED));
    }

    @Test
    void rejectsInvertedAmountBand() {
        CreateDecisionRuleCommand cmd = new CreateDecisionRuleCommand(
                tenant,
                "purchase_request",
                10,
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                null,
                List.of(new ApproverRef("USER", "u_2")));

        assertThatThrownBy(() -> handler().handle(cmd))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.VALIDATION_FAILED));
    }
}
