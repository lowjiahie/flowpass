package com.flowpass.approval.contract.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Public read model of an approval-matrix rule (admin view).
 *
 * @param id           platform rule id
 * @param templateKey  template this rule applies to
 * @param priority     evaluation order (ascending; lower runs first)
 * @param amountMin    optional inclusive lower bound on the {@code amount} variable
 * @param amountMax    optional exclusive upper bound on the {@code amount} variable
 * @param fieldMatches variable field constraints ({@code field -> expected value})
 * @param expression   optional condition expression over the variables, or null
 * @param approvers    resolved first-round approvers when this rule matches
 */
public record DecisionRuleDTO(
        String id,
        String templateKey,
        int priority,
        BigDecimal amountMin,
        BigDecimal amountMax,
        Map<String, String> fieldMatches,
        String expression,
        List<ApproverDTO> approvers) implements Serializable {

    private static final long serialVersionUID = 1L;
}
