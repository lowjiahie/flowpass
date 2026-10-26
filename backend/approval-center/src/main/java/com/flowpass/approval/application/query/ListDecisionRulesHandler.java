package com.flowpass.approval.application.query;

import com.flowpass.approval.contract.dto.ApproverDTO;
import com.flowpass.approval.contract.dto.DecisionRuleDTO;
import com.flowpass.approval.domain.decision.DecisionRule;
import com.flowpass.approval.domain.decision.DecisionRuleRepository;
import com.flowpass.approval.domain.decision.RuleCondition;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.application.common.RuleApproverDtos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read use case: list approval-matrix rules for a tenant (admin view), optionally filtered by template.
 *
 * <p>Pure read-only mapping to the public contract DTO; no business rules here.</p>
 */
@Service
public class ListDecisionRulesHandler {

    private final DecisionRuleRepository ruleRepository;

    public ListDecisionRulesHandler(DecisionRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.RULE_MANAGE + "')")
    @Transactional(readOnly = true)
    public List<DecisionRuleDTO> handle(ListDecisionRulesQuery query) {
        List<DecisionRule> rules = (query.templateKey() == null || query.templateKey().isBlank())
                ? ruleRepository.findAll(query.tenantId())
                : ruleRepository.findByTemplate(query.tenantId(), query.templateKey());
        return rules.stream().map(this::toDto).toList();
    }

    private DecisionRuleDTO toDto(DecisionRule rule) {
        RuleCondition condition = rule.condition();
        return new DecisionRuleDTO(
                rule.id(),
                rule.templateKey(),
                rule.priority(),
                condition.amountMin(),
                condition.amountMax(),
                condition.fieldMatches(),
                condition.expression() == null ? null : condition.expression().source(),
                RuleApproverDtos.of(rule));
    }
}
