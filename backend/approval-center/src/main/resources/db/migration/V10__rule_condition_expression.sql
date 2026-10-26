-- ============================================================================
-- V10: rule condition expressions.
-- A decision rule may carry a condition written in the safe rule-expression language
-- (e.g. "amount > 1000 && department in ['IT','HR']") in addition to the amount band and field matches;
-- all of them must hold for the rule to match.
-- ============================================================================

ALTER TABLE decision_rule
    ADD COLUMN condition_expr TEXT;
