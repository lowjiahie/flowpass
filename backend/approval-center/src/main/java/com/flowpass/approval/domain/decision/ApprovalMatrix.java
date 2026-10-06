package com.flowpass.approval.domain.decision;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Domain service for the deterministic approval matrix.
 *
 * <p>Rules are evaluated by ascending {@code priority}; the first match wins. Resolution stays
 * deterministic and auditable — no rule engine, no expression evaluation. A set with no match yields no
 * candidates (the instance starts with no first-round task, i.e. manual handling).</p>
 */
public final class ApprovalMatrix {

    private ApprovalMatrix() {
    }

    /** Select the highest-priority rule whose condition matches the variables, if any. */
    public static Optional<DecisionRule> select(List<DecisionRule> rules, Map<String, Object> variables) {
        return rules.stream()
                .sorted(Comparator.comparingInt(DecisionRule::priority))
                .filter(rule -> rule.matches(variables))
                .findFirst();
    }
}
