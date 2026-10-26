package com.flowpass.approval.infrastructure.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowpass.approval.application.common.FieldViolation;
import com.flowpass.approval.application.port.FormValidationPort;
import com.flowpass.approval.domain.decision.RuleExpression;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON Schema (2020-12, or draft-07 when declared) implementation of {@link FormValidationPort}.
 *
 * <p>A form schema is written by a tenant admin and evaluated against applicants' input on every start, so the
 * schema is held to a safe subset:</p>
 * <ul>
 *   <li>self-contained — {@code $ref} may only point inside the document ({@code #/...}); {@code $id},
 *       {@code $anchor}, {@code $dynamicRef} and friends are refused, so a schema can never make the server fetch
 *       anything or rebase itself;</li>
 *   <li>no regular expressions — {@code pattern} / {@code patternProperties} are refused (catastrophic
 *       backtracking on attacker-chosen input would burn a request thread); use {@code format}, {@code enum}
 *       and length / range keywords instead;</li>
 *   <li>bounded — schema size, nesting depth and the size of the input that is validated are capped;</li>
 *   <li>business rules — an optional {@code x-rules} list of cross-field conditions (see {@link #checkSchema}),
 *       written in the safe rule-expression language and evaluated only once the types are right;</li>
 *   <li>well-formed — it must itself validate against its meta-schema, describe an object at the root, and
 *       {@code format} is enforced (not merely annotated).</li>
 * </ul>
 * Types are strict: the string {@code "123"} is not a number.
 */
@Component
public class JsonSchemaFormValidator implements FormValidationPort {

    static final int MAX_SCHEMA_BYTES = 64 * 1024;
    static final int MAX_INPUT_BYTES = 256 * 1024;
    static final int MAX_DEPTH = 24;
    static final int MAX_VIOLATIONS = 50;
    private static final int MAX_CACHED_SCHEMAS = 256;
    private static final int MAX_BUSINESS_RULES = 20;
    static final String RULES_KEYWORD = "x-rules";

    private static final String DIALECT_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> DIALECTS_7 = Set.of("http://json-schema.org/draft-07/schema#",
            "http://json-schema.org/draft-07/schema");

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "$id", "id", "$anchor", "$dynamicRef", "$dynamicAnchor", "$recursiveRef", "$recursiveAnchor",
            "$vocabulary", "pattern", "patternProperties");
    /** Keywords whose value maps names to subschemas (the names are data, not keywords). */
    private static final Set<String> SCHEMA_MAPS = Set.of("properties", "$defs", "definitions", "dependentSchemas");
    /** Keywords whose value is a subschema (or, for some, an array of them). */
    private static final Set<String> SCHEMAS = Set.of("items", "additionalProperties", "additionalItems", "not",
            "if", "then", "else", "contains", "propertyNames", "unevaluatedProperties", "unevaluatedItems");
    private static final Set<String> SCHEMA_ARRAYS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");

    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true)
            .pathType(PathType.JSON_PATH)
            .build();
    private final JsonSchemaFactory factory2020 = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final JsonSchemaFactory factory7 = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private final Map<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------------------------------------

    @Override
    public String checkSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException("the form schema is empty");
        }
        JsonNode node;
        String json;
        try {
            node = mapper.valueToTree(schema);
            json = mapper.writeValueAsString(node);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new IllegalArgumentException("the form schema is not valid JSON");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("the form schema exceeds " + MAX_SCHEMA_BYTES + " bytes");
        }
        if (!"object".equals(node.path("type").asText())) {
            throw new IllegalArgumentException("the form schema must describe an object (\"type\": \"object\")");
        }
        JsonSchemaFactory factory = factoryFor(node);
        walk(node, node, 0);
        checkBusinessRules(node);
        try {
            // It must itself be a valid schema of its dialect...
            String dialect = node.has("$schema") ? node.get("$schema").asText() : DIALECT_2020_12;
            Set<ValidationMessage> metaProblems = factory.getSchema(SchemaLocation.of(dialect), config).validate(node);
            if (!metaProblems.isEmpty()) {
                throw new IllegalArgumentException("not a valid JSON Schema: "
                        + metaProblems.iterator().next().getError());
            }
            // ...and compile (this also resolves local $ref targets).
            factory.getSchema(node, config).validate(mapper.createObjectNode());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a valid JSON Schema: " + e.getMessage());
        }
        return json;
    }

    @Override
    public List<FieldViolation> validate(String schemaJson, Map<String, Object> variables) {
        JsonNode input;
        try {
            input = mapper.valueToTree(variables == null ? Map.of() : variables);
            if (mapper.writeValueAsBytes(input).length > MAX_INPUT_BYTES) {
                return List.of(new FieldViolation("", "the input exceeds " + MAX_INPUT_BYTES + " bytes"));
            }
        } catch (IllegalArgumentException | JsonProcessingException e) {
            return List.of(new FieldViolation("", "the input is not valid JSON"));
        }
        Set<ValidationMessage> messages = schemaFor(schemaJson).validate(input);
        List<FieldViolation> violations = messages.stream()
                .map(this::toViolation)
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .limit(MAX_VIOLATIONS)
                .toList();
        if (!violations.isEmpty()) {
            // Cross-field rules assume well-typed fields; report the type problems first.
            return violations;
        }
        return businessRuleViolations(schemaJson, variables == null ? Map.of() : variables);
    }

    // ---- business rules ("x-rules") -------------------------------------------------------------

    /**
     * {@code "x-rules": [{"expression": "endDate > startDate", "message": "End date must be after the start date",
     * "field": "endDate"}]} — each expression must hold for the application to be valid. They are written in the
     * safe rule-expression language and live inside the schema, so they are versioned and snapshotted with it.
     */
    private void checkBusinessRules(JsonNode schema) {
        JsonNode rules = schema.get(RULES_KEYWORD);
        if (rules == null) {
            return;
        }
        if (!rules.isArray() || rules.size() > MAX_BUSINESS_RULES) {
            throw new IllegalArgumentException("'" + RULES_KEYWORD + "' must be a list of at most "
                    + MAX_BUSINESS_RULES + " rules");
        }
        for (JsonNode rule : rules) {
            if (!rule.isObject() || !rule.path("expression").isTextual() || !rule.path("message").isTextual()
                    || rule.path("message").asText().isBlank() || rule.path("message").asText().length() > 200) {
                throw new IllegalArgumentException("each '" + RULES_KEYWORD
                        + "' entry needs an 'expression' and a 'message' (at most 200 characters)");
            }
            if (rule.has("field") && (!rule.get("field").isTextual() || rule.get("field").asText().length() > 100)) {
                throw new IllegalArgumentException("'field' of a '" + RULES_KEYWORD + "' entry must be short text");
            }
            for (Iterator<String> names = rule.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (!Set.of("expression", "message", "field").contains(name)) {
                    throw new IllegalArgumentException("unknown '" + name + "' in a '" + RULES_KEYWORD + "' entry");
                }
            }
            try {
                RuleExpression.parse(rule.get("expression").asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid '" + RULES_KEYWORD + "' expression '"
                        + rule.get("expression").asText() + "': " + e.getMessage());
            }
        }
    }

    private List<FieldViolation> businessRuleViolations(String schemaJson, Map<String, Object> variables) {
        JsonNode rules;
        try {
            rules = mapper.readTree(schemaJson).get(RULES_KEYWORD);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored form schema is not valid JSON", e);
        }
        if (rules == null || !rules.isArray()) {
            return List.of();
        }
        java.util.ArrayList<FieldViolation> violations = new java.util.ArrayList<>();
        for (JsonNode rule : rules) {
            if (!RuleExpression.parse(rule.get("expression").asText()).matches(variables)) {
                violations.add(new FieldViolation(rule.path("field").asText(""), rule.get("message").asText()));
            }
        }
        return violations;
    }

    // ------------------------------------------------------------------------------------------------

    private JsonSchema schemaFor(String schemaJson) {
        JsonSchema cached = compiled.get(schemaJson);
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode node = mapper.readTree(schemaJson);
            JsonSchema schema = factoryFor(node).getSchema(node, config);
            if (compiled.size() >= MAX_CACHED_SCHEMAS) {
                compiled.clear();
            }
            compiled.put(schemaJson, schema);
            return schema;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored form schema is not valid JSON", e);
        }
    }

    private JsonSchemaFactory factoryFor(JsonNode schema) {
        if (!schema.has("$schema")) {
            return factory2020;
        }
        String dialect = schema.get("$schema").asText();
        if (DIALECT_2020_12.equals(dialect)) {
            return factory2020;
        }
        if (DIALECTS_7.contains(dialect)) {
            return factory7;
        }
        throw new IllegalArgumentException("unsupported $schema '" + dialect + "'; use " + DIALECT_2020_12
                + " or http://json-schema.org/draft-07/schema#");
    }

    /** Walk the schema (not the data inside enum / const / default) refusing unsafe keywords and excess depth. */
    private void walk(JsonNode root, JsonNode schema, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("the form schema is nested deeper than " + MAX_DEPTH + " levels");
        }
        if (!schema.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String keyword = field.getKey();
            JsonNode value = field.getValue();
            if (FORBIDDEN_KEYWORDS.contains(keyword)) {
                throw new IllegalArgumentException("'" + keyword + "' is not allowed in a form schema"
                        + ("pattern".equals(keyword) || "patternProperties".equals(keyword)
                        ? " (regular expressions are not supported; use format, enum, or length / range keywords)" : ""));
            }
            if ("$ref".equals(keyword)) {
                String ref = value.isTextual() ? value.asText() : "";
                if (!ref.equals("#") && !ref.startsWith("#/")) {
                    throw new IllegalArgumentException("$ref must point inside the schema ('#' or '#/...')");
                }
                if (!ref.equals("#") && root.at(ref.substring(1)).isMissingNode()) {
                    throw new IllegalArgumentException("$ref '" + ref + "' points to nothing");
                }
            } else if (SCHEMA_MAPS.contains(keyword)) {
                if (value.isObject()) {
                    value.fields().forEachRemaining(entry -> walk(root, entry.getValue(), depth + 1));
                }
            } else if (SCHEMAS.contains(keyword)) {
                if (value.isArray()) {
                    value.forEach(item -> walk(root, item, depth + 1));
                } else {
                    walk(root, value, depth + 1);
                }
            } else if (SCHEMA_ARRAYS.contains(keyword) && value.isArray()) {
                value.forEach(item -> walk(root, item, depth + 1));
            }
        }
    }

    private FieldViolation toViolation(ValidationMessage message) {
        String location = message.getInstanceLocation().toString();
        String field = location.startsWith("$") ? location.substring(1) : location;
        if (field.startsWith(".")) {
            field = field.substring(1);
        }
        if ("required".equals(message.getType()) && message.getProperty() != null) {
            field = field.isEmpty() ? message.getProperty() : field + "." + message.getProperty();
        }
        String text = message.getError() != null ? message.getError() : message.getMessage();
        return new FieldViolation(field, text);
    }
}
