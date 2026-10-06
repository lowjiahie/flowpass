package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Request to define a new approval-matrix rule.
 *
 * <p>The structured condition is optional: with no constraints the rule is a default fallback that
 * matches every input. At least one approver is required.</p>
 *
 * @param templateKey  template this rule applies to (required)
 * @param priority     evaluation order (ascending); defaults server-side when omitted
 * @param amountMin    optional inclusive lower bound on the {@code amount} variable
 * @param amountMax    optional exclusive upper bound on the {@code amount} variable
 * @param fieldMatches optional variable field constraints ({@code field -> expected value})
 * @param approvers    first-round approvers when this rule matches (at least one)
 */
public record CreateDecisionRuleRequest(
        @NotBlank @Size(max = 64) String templateKey,
        Integer priority,
        BigDecimal amountMin,
        BigDecimal amountMax,
        Map<String, String> fieldMatches,
        @NotEmpty List<ApproverDTO> approvers) implements Serializable {

    private static final long serialVersionUID = 1L;
}
