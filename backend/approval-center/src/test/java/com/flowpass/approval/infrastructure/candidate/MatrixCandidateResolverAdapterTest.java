package com.flowpass.approval.infrastructure.candidate;

import com.flowpass.approval.application.port.ApproverResolution;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.decision.DecisionRule;
import com.flowpass.approval.domain.decision.DecisionRuleRepository;
import com.flowpass.approval.domain.decision.RuleCondition;
import com.flowpass.approval.domain.directory.OrgDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Unit tests for {@link MatrixCandidateResolverAdapter}: rule selection plus approver resolution. */
@ExtendWith(MockitoExtension.class)
class MatrixCandidateResolverAdapterTest {

    private final TenantId tenant = new TenantId("t_10086");
    private final ApproverRef initiator = new ApproverRef("USER", "u_1");

    @Mock
    private DecisionRuleRepository ruleRepository;
    @Mock
    private OrgDirectory orgDirectory;

    private MatrixCandidateResolverAdapter adapter() {
        return new MatrixCandidateResolverAdapter(ruleRepository, orgDirectory);
    }

    @Test
    void mapsApproversOfTheSelectedRule() {
        DecisionRule rule = DecisionRule.define(tenant, "purchase_request", 10,
                RuleCondition.amountBand(new BigDecimal("1000.00"), null),
                List.of(new ApproverRef("USER", "u_2"), new ApproverRef("USER", "u_3")));
        when(ruleRepository.findByTemplate(tenant, "purchase_request")).thenReturn(List.of(rule));

        ApproverResolution resolution = adapter().resolve(tenant, "purchase_request", initiator, Map.of("amount", 5000));

        assertThat(resolution.approvers())
                .containsExactly(new CandidateApprover("USER", "u_2"), new CandidateApprover("USER", "u_3"));
        assertThat(resolution.problems()).isEmpty();
    }

    @Test
    void resolvesRelativeAndGroupApproversThroughTheDirectory() {
        DecisionRule rule = DecisionRule.define(tenant, "purchase_request", 10, RuleCondition.any(),
                List.of(new ApproverRef("INITIATOR_MANAGER", "initiator"), new ApproverRef("ROLE", "finance")));
        when(ruleRepository.findByTemplate(tenant, "purchase_request")).thenReturn(List.of(rule));
        when(orgDirectory.managerOf(tenant, "u_1")).thenReturn(Optional.of("u_boss"));

        ApproverResolution resolution = adapter().resolve(tenant, "purchase_request", initiator, Map.of());

        assertThat(resolution.approvers())
                .containsExactly(new CandidateApprover("USER", "u_boss"), new CandidateApprover("ROLE", "finance"));
    }

    @Test
    void reportsProblemsAndNoApproversWhenAnEntryCannotBeResolved() {
        DecisionRule rule = DecisionRule.define(tenant, "purchase_request", 10, RuleCondition.any(),
                List.of(new ApproverRef("INITIATOR_MANAGER", "initiator"), new ApproverRef("USER", "u_fin")));
        when(ruleRepository.findByTemplate(tenant, "purchase_request")).thenReturn(List.of(rule));
        when(orgDirectory.managerOf(tenant, "u_1")).thenReturn(Optional.empty());

        ApproverResolution resolution = adapter().resolve(tenant, "purchase_request", initiator, Map.of());

        assertThat(resolution.approvers()).isEmpty();
        assertThat(resolution.problems()).containsExactly("no manager recorded for u_1");
        assertThat(resolution.failed()).isTrue();
    }

    @Test
    void resolvesNothingWhenNoRuleMatches() {
        DecisionRule rule = DecisionRule.define(tenant, "purchase_request", 10,
                RuleCondition.amountBand(new BigDecimal("10000.00"), null),
                List.of(new ApproverRef("USER", "u_2")));
        when(ruleRepository.findByTemplate(tenant, "purchase_request")).thenReturn(List.of(rule));

        ApproverResolution resolution = adapter().resolve(tenant, "purchase_request", initiator, Map.of("amount", 5));

        assertThat(resolution.approvers()).isEmpty();
        assertThat(resolution.failed()).as("no rule is not a failure").isFalse();
    }

    @Test
    void resolvesNothingWhenTemplateHasNoRules() {
        when(ruleRepository.findByTemplate(tenant, "purchase_request")).thenReturn(List.of());

        assertThat(adapter().resolve(tenant, "purchase_request", initiator, Map.of()).approvers()).isEmpty();
    }
}
