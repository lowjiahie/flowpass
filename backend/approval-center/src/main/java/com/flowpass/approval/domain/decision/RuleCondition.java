package com.flowpass.approval.domain.decision;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Structured, deterministic condition for a {@link DecisionRule}.
 *
 * <p>Two independent constraint kinds, all of which must hold for a match:
 * <ul>
 *   <li>amount band — {@code amountMin <= amount < amountMax} (each bound optional);</li>
 *   <li>field matches — every entry must equal the instance variable's string value;</li>
 *   <li>an optional {@link RuleExpression} — a safe boolean condition over the variables, for anything the two
 *       above cannot say (comparisons, "one of", AND / OR / NOT, cross-field checks).</li>
 * </ul>
 * A condition with no constraints is a default/fallback rule: it matches every input.</p>
 */
public record RuleCondition(BigDecimal amountMin, BigDecimal amountMax, Map<String, String> fieldMatches,
                            RuleExpression expression) {

    public static final String AMOUNT_VARIABLE = "amount";

    /** A condition without an expression: amount band and / or field matches only. */
    public RuleCondition(BigDecimal amountMin, BigDecimal amountMax, Map<String, String> fieldMatches) {
        this(amountMin, amountMax, fieldMatches, null);
    }

    public RuleCondition {
        fieldMatches = (fieldMatches == null) ? Map.of() : Map.copyOf(fieldMatches);
        if (amountMin != null && amountMax != null && amountMin.compareTo(amountMax) >= 0) {
            throw new IllegalArgumentException("amountMin must be less than amountMax");
        }
    }

    /** A condition with no constraints — matches every variable set (default rule). */
    public static RuleCondition any() {
        return new RuleCondition(null, null, Map.of(), null);
    }

    /** A condition restricting on the {@code amount} variable only. */
    public static RuleCondition amountBand(BigDecimal min, BigDecimal max) {
        return new RuleCondition(min, max, Map.of(), null);
    }

    /** True when this condition has no constraints (a fallback rule). */
    public boolean isDefault() {
        return amountMin == null && amountMax == null && fieldMatches.isEmpty() && expression == null;
    }

    /** True when the given instance variables satisfy every configured constraint. */
    public boolean matches(Map<String, Object> variables) {
        return amountWithinBand(variables) && fieldsMatch(variables)
                && (expression == null || expression.matches(variables));
    }

    private boolean amountWithinBand(Map<String, Object> variables) {
        if (amountMin == null && amountMax == null) {
            return true;
        }
        BigDecimal amount = toBigDecimal(variables == null ? null : variables.get(AMOUNT_VARIABLE));
        if (amount == null) {
            return false;
        }
        if (amountMin != null && amount.compareTo(amountMin) < 0) {
            return false;
        }
        return amountMax == null || amount.compareTo(amountMax) < 0;
    }

    private boolean fieldsMatch(Map<String, Object> variables) {
        for (Map.Entry<String, String> constraint : fieldMatches.entrySet()) {
            Object actual = variables == null ? null : variables.get(constraint.getKey());
            if (actual == null || !constraint.getValue().equals(String.valueOf(actual))) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
