package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.contract.dto.ApproverDTO;
import com.flowpass.approval.contract.dto.DecisionRuleDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.decision.DecisionRule;
import com.flowpass.approval.domain.decision.DecisionRuleRepository;
import com.flowpass.approval.domain.decision.RuleCondition;
import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Define-rule use case. Builds the {@link DecisionRule} aggregate (which enforces its invariants) and
 * persists it. Domain validation failures (blank key, empty approvers, inverted band) are surfaced as a
 * stable {@code VALIDATION_FAILED} business error.
 */
@Service
public class CreateDecisionRuleHandler {

    private final DecisionRuleRepository ruleRepository;

    public CreateDecisionRuleHandler(DecisionRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.RULE_MANAGE + "')")
    @Transactional
    public DecisionRuleDTO handle(CreateDecisionRuleCommand cmd) {
        DecisionRule rule;
        try {
            rule = DecisionRule.define(
                    cmd.tenantId(),
                    cmd.templateKey(),
                    cmd.priority(),
                    new RuleCondition(cmd.amountMin(), cmd.amountMax(), cmd.fieldMatches()),
                    cmd.approvers());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        ruleRepository.save(rule);
        return toDto(rule);
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
                rule.approvers().stream()
                        .map(a -> new ApproverDTO(a.type(), a.id()))
                        .toList());
    }
}
