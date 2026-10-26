package com.flowpass.approval.application.common;

import com.flowpass.approval.contract.dto.ApproverDTO;
import com.flowpass.approval.domain.decision.DecisionRule;

import java.util.ArrayList;
import java.util.List;

/** Maps the approval chain of a rule to its public form: every approver tagged with its round. */
public final class RuleApproverDtos {

    private RuleApproverDtos() {
    }

    /** The rule's approvers, each tagged with the round it belongs to (1-based, in the order the rounds open). */
    public static List<ApproverDTO> of(DecisionRule rule) {
        List<ApproverDTO> out = new ArrayList<>();
        for (int i = 0; i < rule.rounds().size(); i++) {
            int round = i + 1;
            rule.rounds().get(i).forEach(a -> out.add(new ApproverDTO(a.type(), a.id(), round)));
        }
        return out;
    }
}
