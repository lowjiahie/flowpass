package com.flowpass.approval.infrastructure.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowpass.approval.application.common.FieldViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The form-schema adapter: what input is accepted or refused, and — because a schema is tenant-authored — which
 * schemas are refused (remote references, regular expressions, oversize, malformed).
 */
class JsonSchemaFormValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonSchemaFormValidator validator = new JsonSchemaFormValidator();

    private static Map<String, Object> schema(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String PURCHASE = """
            {
              "type": "object",
              "required": ["amount", "currency", "reason"],
              "additionalProperties": false,
              "properties": {
                "amount":   {"type": "number", "exclusiveMinimum": 0, "maximum": 1000000},
                "currency": {"enum": ["MYR", "CNY", "USD"]},
                "reason":   {"type": "string", "minLength": 5, "maxLength": 200},
                "email":    {"type": "string", "format": "email"},
                "needBy":   {"type": "string", "format": "date"},
                "urgent":   {"type": "boolean"},
                "items": {
                  "type": "array", "minItems": 1, "maxItems": 3,
                  "items": {"type": "object", "required": ["sku", "qty"],
                            "properties": {"sku": {"type": "string"}, "qty": {"type": "integer", "minimum": 1}}}
                }
              }
            }
            """;

    private List<FieldViolation> check(Map<String, Object> input) {
        return validator.validate(validator.checkSchema(schema(PURCHASE)), input);
    }

    private static Map<String, Object> validInput() {
        return new java.util.HashMap<>(Map.of("amount", 100, "currency", "MYR", "reason", "Laptops for the team"));
    }

    private static List<String> fields(List<FieldViolation> violations) {
        return violations.stream().map(FieldViolation::field).toList();
    }

    // ---- accepted input ------------------------------------------------------------------------------

    @Test
    void validInputPasses() {
        assertThat(check(validInput())).isEmpty();
    }

    @Test
    void allTheDataTypesTheSchemaDescribesAreAccepted() {
        Map<String, Object> input = validInput();
        input.put("amount", new BigDecimal("1234.56"));
        input.put("email", "alice@example.com");
        input.put("needBy", "2026-12-31");
        input.put("urgent", true);
        input.put("items", List.of(Map.of("sku", "A-1", "qty", 2), Map.of("sku", "B-2", "qty", 1)));

        assertThat(check(input)).isEmpty();
    }

    // ---- refused input -------------------------------------------------------------------------------

    @Test
    void aMissingRequiredFieldIsNamed() {
        Map<String, Object> input = validInput();
        input.remove("reason");

        List<FieldViolation> violations = check(input);

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.field()).isEqualTo("reason");
            assertThat(v.message()).contains("required");
        });
    }

    @Test
    void aNumberSentAsAStringIsNotANumber() {
        Map<String, Object> input = validInput();
        input.put("amount", "100");

        assertThat(check(input)).singleElement().satisfies(v -> {
            assertThat(v.field()).isEqualTo("amount");
            assertThat(v.message()).contains("number");
        });
    }

    @Test
    void notANumberAtAllIsRefused() {
        Map<String, Object> input = validInput();
        input.put("amount", "abc");

        assertThat(fields(check(input))).containsExactly("amount");
    }

    @Test
    void rangesLengthsAndEnumsAreEnforced() {
        Map<String, Object> input = validInput();
        input.put("amount", -5);
        input.put("currency", "EUR");
        input.put("reason", "no");

        assertThat(fields(check(input))).containsExactlyInAnyOrder("amount", "currency", "reason");
    }

    @Test
    void zeroIsRefusedWhenTheMinimumIsExclusive() {
        Map<String, Object> input = validInput();
        input.put("amount", 0);

        assertThat(fields(check(input))).containsExactly("amount");
    }

    @Test
    void anUnknownFieldIsRefusedWhenAdditionalPropertiesIsFalse() {
        Map<String, Object> input = validInput();
        input.put("surprise", "x");

        assertThat(check(input)).singleElement().satisfies(v -> assertThat(v.message()).contains("surprise"));
    }

    @Test
    void formatsAreEnforcedNotMerelyAnnotated() {
        Map<String, Object> input = validInput();
        input.put("email", "not-an-email");
        input.put("needBy", "31/12/2026");

        assertThat(fields(check(input))).containsExactlyInAnyOrder("email", "needBy");
    }

    @Test
    void problemsInsideArraysCarryTheirPath() {
        Map<String, Object> input = validInput();
        input.put("items", List.of(Map.of("sku", "A-1", "qty", 0), Map.of("qty", 1)));

        assertThat(fields(check(input))).containsExactlyInAnyOrder("items[0].qty", "items[1].sku");
    }

    @Test
    void arrayLengthLimitsAreEnforced() {
        Map<String, Object> input = validInput();
        input.put("items", List.of());

        assertThat(fields(check(input))).containsExactly("items");
    }

    @Test
    void everyProblemIsReportedAtOnceInAStableOrder() {
        List<FieldViolation> violations = check(Map.of("amount", "x", "currency", "EUR"));

        assertThat(fields(violations)).containsExactly("amount", "currency", "reason");
    }

    @Test
    void aNullValueIsNotAString() {
        Map<String, Object> input = validInput();
        input.put("reason", null);

        assertThat(fields(check(input))).containsExactly("reason");
    }

    @Test
    void oversizedInputIsRefusedWithoutBeingEvaluated() {
        Map<String, Object> input = validInput();
        input.put("reason", "x".repeat(JsonSchemaFormValidator.MAX_INPUT_BYTES));

        assertThat(check(input)).singleElement().satisfies(v -> assertThat(v.message()).contains("exceeds"));
    }

    // ---- acceptable schemas ----------------------------------------------------------------------------

    @Test
    void checkSchemaReturnsCanonicalJson() {
        String json = validator.checkSchema(schema(PURCHASE));

        assertThat(json).startsWith("{").contains("\"required\"");
    }

    @Test
    void localReferencesAreAllowed() {
        Map<String, Object> schema = schema("""
                {"type":"object","properties":{"home":{"$ref":"#/$defs/address"},"work":{"$ref":"#/$defs/address"}},
                 "$defs":{"address":{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}}}
                """);

        String json = validator.checkSchema(schema);

        assertThat(validator.validate(json, Map.of("home", Map.of("city", "KL"), "work", Map.of()))).hasSize(1);
    }

    @Test
    void draft07SchemasAreSupportedWhenDeclared() {
        Map<String, Object> schema = schema("""
                {"$schema":"http://json-schema.org/draft-07/schema#","type":"object","required":["a"],
                 "properties":{"a":{"type":"integer"}}}
                """);

        assertThat(validator.validate(validator.checkSchema(schema), Map.of("a", "x"))).hasSize(1);
    }

    @Test
    void aFieldMayBeNamedLikeAForbiddenKeyword() {
        // "pattern" and "$id" are only forbidden as schema keywords, not as the name of a property.
        Map<String, Object> schema = schema("""
                {"type":"object","required":["pattern"],"properties":{"pattern":{"type":"string"},"id":{"type":"string"}}}
                """);

        String json = validator.checkSchema(schema);

        assertThat(validator.validate(json, Map.of("pattern", "x", "id", "y"))).isEmpty();
    }

    // ---- refused schemas -------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"https://evil.example/schema.json\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"file:///etc/passwd\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"other.json#/x\"}}}",
            "{\"type\":\"object\",\"$id\":\"https://evil.example/\"}",
            "{\"type\":\"object\",\"$dynamicRef\":\"#x\"}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$anchor\":\"x\",\"type\":\"string\"}}}"})
    void anythingThatCouldMakeTheServerFetchOrRebaseIsRefused(String json) {
        assertThatThrownBy(() -> validator.checkSchema(schema(json))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regularExpressionsAreRefusedBecauseOfBacktracking() {
        assertThatThrownBy(() -> validator.checkSchema(schema(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"(a+)+$\"}}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern").hasMessageContaining("regular expressions");
        assertThatThrownBy(() -> validator.checkSchema(schema(
                "{\"type\":\"object\",\"patternProperties\":{\"^x\":{\"type\":\"string\"}}}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPatternHiddenInANestedSubschemaIsStillFound() {
        assertThatThrownBy(() -> validator.checkSchema(schema("""
                {"type":"object","properties":{"a":{"type":"array","items":{"anyOf":[{"type":"string","pattern":"x"}]}}}}
                """))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pattern");
    }

    @Test
    void theRootMustDescribeAnObject() {
        assertThatThrownBy(() -> validator.checkSchema(schema("{\"type\":\"string\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("object");
        assertThatThrownBy(() -> validator.checkSchema(schema("{\"properties\":{}}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnsupportedDialectIsRefused() {
        assertThatThrownBy(() -> validator.checkSchema(schema(
                "{\"$schema\":\"http://json-schema.org/draft-04/schema#\",\"type\":\"object\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported $schema");
    }

    @Test
    void aSchemaThatIsNotValidAgainstItsMetaSchemaIsRefused() {
        assertThatThrownBy(() -> validator.checkSchema(schema(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"strng\"}}}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a valid JSON Schema");
        assertThatThrownBy(() -> validator.checkSchema(schema(
                "{\"type\":\"object\",\"required\":\"a\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLocalReferenceToNowhereIsRefused() {
        assertThatThrownBy(() -> validator.checkSchema(schema(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/missing\"}}}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyAndOversizedAndTooDeepSchemasAreRefused() {
        assertThatThrownBy(() -> validator.checkSchema(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.checkSchema(null)).isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> huge = schema("{\"type\":\"object\",\"description\":\""
                + "x".repeat(JsonSchemaFormValidator.MAX_SCHEMA_BYTES) + "\"}");
        assertThatThrownBy(() -> validator.checkSchema(huge)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");

        StringBuilder deep = new StringBuilder("{\"type\":\"object\"");
        int levels = JsonSchemaFormValidator.MAX_DEPTH + 5;
        for (int i = 0; i < levels; i++) {
            deep.append(",\"properties\":{\"a\":{\"type\":\"object\"");
        }
        deep.append("}".repeat(levels * 2)).append("}");
        assertThatThrownBy(() -> validator.checkSchema(schema(deep.toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deeper");
    }

    // ---- business rules ("x-rules") --------------------------------------------------------------------

    private static final String LEAVE = """
            {
              "type": "object",
              "required": ["startDate", "days"],
              "properties": {
                "startDate": {"type": "string", "format": "date"},
                "endDate":   {"type": "string", "format": "date"},
                "days":      {"type": "integer", "minimum": 1},
                "budget":    {"type": "number"},
                "total":     {"type": "number"}
              },
              "x-rules": [
                {"expression": "endDate == null || endDate > startDate",
                 "message": "The end date must be after the start date", "field": "endDate"},
                {"expression": "total == null || budget == null || total <= budget * 1.1",
                 "message": "The total may exceed the budget by at most 10%"}
              ]
            }
            """;

    private List<FieldViolation> leave(Map<String, Object> input) {
        return validator.validate(validator.checkSchema(schema(LEAVE)), input);
    }

    @Test
    void aCrossFieldRuleThatHoldsPasses() {
        assertThat(leave(Map.of("startDate", "2026-03-01", "endDate", "2026-03-05", "days", 4,
                "budget", 100, "total", 105))).isEmpty();
    }

    @Test
    void aCrossFieldRuleThatFailsReportsItsMessageOnItsField() {
        List<FieldViolation> violations = leave(Map.of("startDate", "2026-03-05", "endDate", "2026-03-01", "days", 4));

        assertThat(violations).containsExactly(
                new FieldViolation("endDate", "The end date must be after the start date"));
    }

    @Test
    void aRuleWithoutAFieldIsReportedOnTheWholeInput() {
        List<FieldViolation> violations = leave(Map.of("startDate", "2026-03-01", "days", 4, "budget", 100, "total", 200));

        assertThat(violations).containsExactly(
                new FieldViolation("", "The total may exceed the budget by at most 10%"));
    }

    @Test
    void everyFailingRuleIsReportedTogether() {
        List<FieldViolation> violations = leave(Map.of("startDate", "2026-03-05", "endDate", "2026-03-01", "days", 4,
                "budget", 100, "total", 200));

        assertThat(violations).hasSize(2);
    }

    @Test
    void businessRulesWaitUntilTheTypesAreRight() {
        // days is text: only the type problem is reported, not rules that would have to guess at it.
        List<FieldViolation> violations = leave(Map.of("startDate", "2026-03-05", "endDate", "2026-03-01", "days", "four"));

        assertThat(fields(violations)).containsExactly("days");
    }

    @Test
    void optionalFieldsNeedAnExplicitGuardInTheRule() {
        // endDate absent: the rule's own "endDate == null ||" lets it pass.
        assertThat(leave(Map.of("startDate", "2026-03-01", "days", 4))).isEmpty();
    }

    @Test
    void anInvalidRuleExpressionIsRefusedWhenTheSchemaIsSaved() {
        Map<String, Object> bad = schema("""
                {"type":"object","x-rules":[{"expression":"endDate => startDate","message":"nope"}]}
                """);

        assertThatThrownBy(() -> validator.checkSchema(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-rules").hasMessageContaining("endDate => startDate");
    }

    @Test
    void aRuleThatCallsAMethodIsRefused() {
        Map<String, Object> bad = schema("""
                {"type":"object","x-rules":[{"expression":"runtime.exec('calc')","message":"nope"}]}
                """);

        assertThatThrownBy(() -> validator.checkSchema(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedRuleEntriesAreRefused() {
        for (String rules : List.of(
                "\"x-rules\": {\"expression\": \"true\", \"message\": \"m\"}",
                "\"x-rules\": [{\"message\": \"m\"}]",
                "\"x-rules\": [{\"expression\": \"true\"}]",
                "\"x-rules\": [{\"expression\": \"true\", \"message\": \"\"}]",
                "\"x-rules\": [{\"expression\": \"true\", \"message\": \"m\", \"extra\": 1}]",
                "\"x-rules\": [{\"expression\": 5, \"message\": \"m\"}]")) {
            Map<String, Object> bad = schema("{\"type\":\"object\"," + rules + "}");
            assertThatThrownBy(() -> validator.checkSchema(bad)).as(rules).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void tooManyRulesAreRefused() {
        StringBuilder rules = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            rules.append(i == 0 ? "" : ",").append("{\"expression\":\"true\",\"message\":\"m\"}");
        }
        Map<String, Object> bad = schema("{\"type\":\"object\",\"x-rules\":[" + rules + "]}");

        assertThatThrownBy(() -> validator.checkSchema(bad)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void aFieldMayBeNamedLikeTheRulesKeyword() {
        Map<String, Object> schema = schema("""
                {"type":"object","properties":{"x-rules":{"type":"string"}}}
                """);

        assertThat(validator.validate(validator.checkSchema(schema), Map.of("x-rules", "fine"))).isEmpty();
    }
}
