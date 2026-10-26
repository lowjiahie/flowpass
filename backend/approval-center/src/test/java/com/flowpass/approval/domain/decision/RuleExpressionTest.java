package com.flowpass.approval.domain.decision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The safe rule-expression language: what it can say, how it treats missing / mistyped data, what it refuses. */
class RuleExpressionTest {

    private static final Map<String, Object> VARS = Map.ofEntries(
            Map.entry("amount", 1500),
            Map.entry("price", new BigDecimal("12.50")),
            Map.entry("department", "IT"),
            Map.entry("region", "APAC"),
            Map.entry("reason", "new laptops for the team"),
            Map.entry("urgent", true),
            Map.entry("tags", List.of("it", "hardware")),
            Map.entry("startDate", "2026-03-01"),
            Map.entry("endDate", "2026-03-15"),
            Map.entry("text", "250"),
            Map.entry("customer", Map.of("tier", "gold", "address", Map.of("country", "MY"))));

    private static boolean eval(String expression) {
        return RuleExpression.parse(expression).matches(VARS);
    }

    // ---- comparison ----------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '~', textBlock = """
            amount > 1000            ~ true
            amount > 1500            ~ false
            amount >= 1500           ~ true
            amount < 2000            ~ true
            amount <= 1499           ~ false
            amount == 1500           ~ true
            amount != 1500           ~ false
            amount == 1500.00        ~ true
            price == 12.5            ~ true
            price < 13               ~ true
            department == 'IT'       ~ true
            department == "IT"       ~ true
            department != 'HR'       ~ true
            department == 'it'       ~ false
            """)
    void comparisons(String expression, boolean expected) {
        assertThat(eval(expression)).isEqualTo(expected);
    }

    @Test
    void typesAreStrictSoAnAmountSentAsTextIsNotANumber() {
        assertThat(eval("text == 250")).isFalse();
        assertThat(eval("text > 100")).isFalse();
        assertThat(eval("text == '250'")).isTrue();
    }

    @Test
    void datesInIsoFormOrderLexicographicallyWhichIsChronologically() {
        assertThat(eval("endDate > startDate")).isTrue();
        assertThat(eval("startDate >= endDate")).isFalse();
        assertThat(eval("endDate > '2026-03-10' && endDate < '2026-04-01'")).isTrue();
    }

    // ---- logic ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '~', textBlock = """
            amount > 1000 && department == 'IT'                      ~ true
            amount > 1000 && department == 'HR'                      ~ false
            amount > 5000 || department == 'IT'                      ~ true
            amount > 5000 or department == 'HR'                      ~ false
            amount > 1000 and department == 'IT'                     ~ true
            !(amount > 5000)                                         ~ true
            not (amount > 5000)                                      ~ true
            !urgent                                                  ~ false
            urgent && !(region == 'EU')                              ~ true
            (amount > 5000 || urgent) && department == 'IT'          ~ true
            amount > 5000 || amount < 100 || urgent                  ~ true
            true                                                     ~ true
            false                                                    ~ false
            """)
    void booleanLogic(String expression, boolean expected) {
        assertThat(eval(expression)).isEqualTo(expected);
    }

    @Test
    void andBindsTighterThanOr() {
        // true || (false && false)  vs  (true || false) && false
        assertThat(eval("true || false && false")).isTrue();
    }

    @Test
    void anythingThatIsNotTheBooleanTrueCountsAsFalse() {
        assertThat(eval("department")).isFalse();
        assertThat(eval("missing")).isFalse();
        assertThat(eval("amount")).isFalse();
        assertThat(eval("!missing")).isTrue();
    }

    // ---- membership ----------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '~', textBlock = """
            department in ['IT', 'HR']              ~ true
            department in ['FIN', 'HR']             ~ false
            department not in ['FIN', 'HR']         ~ true
            department not in ['IT']                ~ false
            amount in [1000, 1500, 2000]            ~ true
            amount in [1000.0, 1500.00]             ~ true
            region in ['APAC', 'EMEA'] && urgent    ~ true
            department in []                        ~ false
            """)
    void inAndNotIn(String expression, boolean expected) {
        assertThat(eval(expression)).isEqualTo(expected);
    }

    @Test
    void inWorksAgainstAListVariable() {
        assertThat(eval("'hardware' in tags")).isTrue();
        assertThat(eval("'software' in tags")).isFalse();
        assertThat(eval("tags contains 'it'")).isTrue();
    }

    @Test
    void containsFindsASubstring() {
        assertThat(eval("reason contains 'laptop'")).isTrue();
        assertThat(eval("reason contains 'server'")).isFalse();
        assertThat(eval("amount contains 'x'")).isFalse();
    }

    // ---- arithmetic ----------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '~', textBlock = """
            amount + 500 == 2000              ~ true
            amount - 500 == 1000              ~ true
            amount * 2 == 3000                ~ true
            amount / 3 == 500                 ~ true
            amount > price * 100              ~ true
            -amount < 0                       ~ true
            2 + 3 * 4 == 14                   ~ true
            (2 + 3) * 4 == 20                 ~ true
            10 - 2 - 3 == 5                   ~ true
            amount > 1000 + 400               ~ true
            """)
    void arithmetic(String expression, boolean expected) {
        assertThat(eval(expression)).isEqualTo(expected);
    }

    @Test
    void divisionByZeroAndArithmeticOnTextGiveNullSoTheConditionIsSimplyFalse() {
        assertThat(eval("amount / 0 > 1")).isFalse();
        assertThat(eval("department * 2 == 4")).isFalse();
        assertThat(RuleExpression.parse("amount / 0").evaluate(VARS)).isNull();
    }

    @Test
    void stringsConcatenate() {
        assertThat(eval("department + '-' + region == 'IT-APAC'")).isTrue();
    }

    // ---- missing data --------------------------------------------------------------------------------

    @Test
    void aMissingVariableIsNullAndNeverMatchesAnOrdering() {
        assertThat(eval("missing > 1")).isFalse();
        assertThat(eval("missing < 1")).isFalse();
        assertThat(eval("missing >= 0 || missing <= 0")).isFalse();
        assertThat(eval("missing == null")).isTrue();
        assertThat(eval("missing != null")).isFalse();
        assertThat(eval("department != null")).isTrue();
    }

    @Test
    void anOptionalFieldCanBeGuardedExplicitly() {
        Map<String, Object> noEnd = Map.of("startDate", "2026-03-01");
        RuleExpression rule = RuleExpression.parse("endDate == null || endDate > startDate");

        assertThat(rule.matches(noEnd)).isTrue();
        assertThat(rule.matches(Map.of("startDate", "2026-03-01", "endDate", "2026-02-01"))).isFalse();
    }

    @Test
    void nullAndEmptyVariableMapsAreFine() {
        assertThat(RuleExpression.parse("amount > 1").matches(null)).isFalse();
        assertThat(RuleExpression.parse("amount == null").matches(Map.of())).isTrue();
    }

    @Test
    void mixedTypesAreNeverOrderedOrEqual() {
        assertThat(eval("amount > 'abc'")).isFalse();
        assertThat(eval("amount == 'abc'")).isFalse();
        assertThat(eval("urgent == 1")).isFalse();
    }

    // ---- nested values -------------------------------------------------------------------------------

    @Test
    void nestedFieldsAreReadByPath() {
        assertThat(eval("customer.tier == 'gold'")).isTrue();
        assertThat(eval("customer.address.country in ['MY', 'SG']")).isTrue();
        assertThat(eval("customer.nothing.deeper == null")).isTrue();
        assertThat(eval("department.length == null")).as("no property access on text").isTrue();
    }

    @Test
    void aWholeObjectIsNotAComparableValue() {
        assertThat(eval("customer == null")).isTrue();
    }

    // ---- bookkeeping ---------------------------------------------------------------------------------

    @Test
    void itReportsWhichVariablesItReads() {
        assertThat(RuleExpression.parse("amount > price && customer.tier in ['gold'] || urgent").variables())
                .containsExactlyInAnyOrder("amount", "price", "customer", "urgent");
    }

    @Test
    void sourceIsKeptTrimmedAndEqualityIsBySource() {
        RuleExpression a = RuleExpression.parse("  amount > 1  ");

        assertThat(a.source()).isEqualTo("amount > 1");
        assertThat(a).isEqualTo(RuleExpression.parse("amount > 1")).hasSameHashCodeAs(RuleExpression.parse("amount > 1"));
        assertThat(a).hasToString("amount > 1");
    }

    // ---- refused input -------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "amount >",
            "> 5",
            "amount > 1 2",
            "(amount > 1",
            "amount > 1)",
            "amount = 1",
            "amount & 1",
            "amount | 1",
            "amount > 1 > 0",
            "a < b < c",
            "amount in",
            "amount in [1, 2",
            "amount in [1 2]",
            "'unterminated",
            "'a\\nb' == x",
            "department == 'a' + ",
            "x.",
            "x..y",
            ".x",
            "5.",
            "and",
            "in x",
            "x contains",
            "x ? 1 : 2",
            "x; y",
            "x # y",
            "${x}",
            "x @ y",
            "{a: 1}"})
    void malformedInputIsRefusedWithTheReason(String expression) {
        assertThatThrownBy(() -> RuleExpression.parse(expression)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "execution.getVariable('x')",
            "runtimeService.deleteProcessInstance('a', 'b')",
            "T(java.lang.Runtime).getRuntime().exec('calc')",
            "amount.toString() == '1'",
            "size(tags) > 1",
            "foo (1)",
            "'x'.length()"})
    void callsToFunctionsAndMethodsAreRefused(String expression) {
        assertThatThrownBy(() -> RuleExpression.parse(expression)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorsSayWhere() {
        assertThatThrownBy(() -> RuleExpression.parse("amount > > 1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("position 9");
        assertThatThrownBy(() -> RuleExpression.parse("amount = 1"))
                .hasMessageContaining("use ==");
        assertThatThrownBy(() -> RuleExpression.parse("a < b < c"))
                .hasMessageContaining("cannot be chained");
        assertThatThrownBy(() -> RuleExpression.parse("foo(1)"))
                .hasMessageContaining("cannot be called");
    }

    // ---- limits --------------------------------------------------------------------------------------

    @Test
    void anOverlongConditionIsRefused() {
        assertThatThrownBy(() -> RuleExpression.parse("a == 1" + " || a == 1".repeat(RuleExpression.MAX_LENGTH)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("longer than");
    }

    @Test
    void deepNestingIsRefusedInsteadOfOverflowingTheStack() {
        String deepParens = "(".repeat(RuleExpression.MAX_DEPTH + 5) + "true" + ")".repeat(RuleExpression.MAX_DEPTH + 5);
        assertThatThrownBy(() -> RuleExpression.parse(deepParens))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nested too deeply");

        String deepNots = "!".repeat(RuleExpression.MAX_DEPTH + 5) + "true";
        assertThatThrownBy(() -> RuleExpression.parse(deepNots)).isInstanceOf(IllegalArgumentException.class);

        String deepMinus = "-".repeat(RuleExpression.MAX_DEPTH + 5) + "1";
        assertThatThrownBy(() -> RuleExpression.parse(deepMinus)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHugeListLiteralIsRefused() {
        String items = String.join(",", java.util.Collections.nCopies(RuleExpression.MAX_LIST_SIZE + 1, "1"));
        assertThatThrownBy(() -> RuleExpression.parse("x in [" + items + "]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most");
    }

    @Test
    void theLimitsStillAllowReasonableConditions() {
        String ok = "(".repeat(RuleExpression.MAX_DEPTH - 2) + "true" + ")".repeat(RuleExpression.MAX_DEPTH - 2);
        assertThat(RuleExpression.parse(ok).matches(Map.of())).isTrue();
    }
}
