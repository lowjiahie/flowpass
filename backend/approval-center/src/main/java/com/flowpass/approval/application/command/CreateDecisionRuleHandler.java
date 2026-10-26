package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.contract.dto.ApproverDTO;
import com.flowpass.approval.contract.dto.DecisionRuleDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.decision.DecisionRule;
import com.flowpass.approval.domain.decision.DecisionRuleRepository;
import com.flowpass.approval.domain.decision.RuleCondition;
import com.flowpass.approval.domain.decision.RuleExpression;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.application.common.RuleApproverDtos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
            rule = DecisionRule.defineRounds(
                    cmd.tenantId(),
                    cmd.templateKey(),
                    cmd.priority(),
                    new RuleCondition(cmd.amountMin(), cmd.amountMax(), cmd.fieldMatches(),
                            cmd.expression() == null || cmd.expression().isBlank()
                                    ? null : RuleExpression.parse(cmd.expression())),
                    groupByRound(cmd.approvers(), cmd.rounds()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        ruleRepository.save(rule);
        return toDto(rule);
    }

    /** Group the flat approver list into rounds ordered by round number (round 1 when none is given). */
    private static List<List<ApproverRef>> groupByRound(List<ApproverRef> approvers, List<Integer> rounds) {
        if (approvers == null) {
            return null;
        }
        java.util.TreeMap<Integer, List<ApproverRef>> byRound = new java.util.TreeMap<>();
        for (int i = 0; i < approvers.size(); i++) {
            Integer round = rounds == null || i >= rounds.size() || rounds.get(i) == null ? Integer.valueOf(1) : rounds.get(i);
            if (round < 1 || round > DecisionRule.MAX_ROUNDS) {
                throw new IllegalArgumentException("round must be between 1 and " + DecisionRule.MAX_ROUNDS
                        + " but was " + round);
            }
            byRound.computeIfAbsent(round, r -> new java.util.ArrayList<>()).add(approvers.get(i));
        }
        return List.copyOf(byRound.values());
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
