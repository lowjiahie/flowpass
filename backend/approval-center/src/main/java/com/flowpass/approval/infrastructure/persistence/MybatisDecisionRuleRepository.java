package com.flowpass.approval.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.decision.DecisionRule;
import com.flowpass.approval.domain.decision.DecisionRuleRepository;
import com.flowpass.approval.domain.decision.RuleCondition;
import com.flowpass.approval.domain.decision.RuleExpression;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Adapter implementing the {@link DecisionRuleRepository} port on top of MyBatis. Maps the aggregate to
 * the {@code decision_rule} row; the {@code field_matches} and {@code approvers} JSONB columns are
 * (de)serialised here so the domain stays free of JSON concerns.
 */
@Repository
public class MybatisDecisionRuleRepository implements DecisionRuleRepository {

    private static final TypeReference<Map<String, String>> FIELD_MATCHES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> APPROVERS_TYPE = new TypeReference<>() {
    };

    private final DecisionRuleMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisDecisionRuleRepository(DecisionRuleMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DecisionRule> findByTemplate(TenantId tenantId, String templateKey) {
        return mapper.findByTemplate(tenantId.value(), templateKey).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<DecisionRule> findAll(TenantId tenantId) {
        return mapper.findAll(tenantId.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void save(DecisionRule rule) {
        DecisionRuleRow row = new DecisionRuleRow();
        row.setId(rule.id());
        row.setTenantId(rule.tenantId().value());
        row.setTemplateKey(rule.templateKey());
        row.setPriority(rule.priority());
        row.setAmountMin(rule.condition().amountMin());
        row.setAmountMax(rule.condition().amountMax());
        row.setFieldMatches(rule.condition().fieldMatches().isEmpty()
                ? null
                : toJson(rule.condition().fieldMatches()));
        row.setConditionExpr(rule.condition().expression() == null ? null : rule.condition().expression().source());
        // Each approver is stored with its approval round; a stored entry without one is round 1.
        List<Map<String, Object>> stored = new java.util.ArrayList<>();
        for (int i = 0; i < rule.rounds().size(); i++) {
            int round = i + 1;
            rule.rounds().get(i).forEach(a -> stored.add(Map.of("type", a.type(), "id", a.id(), "round", round)));
        }
        row.setApprovers(toJson(stored));
        mapper.insert(row);
    }

    private DecisionRule toDomain(DecisionRuleRow row) {
        return DecisionRule.hydrateRounds(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getTemplateKey(),
                row.getPriority(),
                new RuleCondition(row.getAmountMin(), row.getAmountMax(), parseFieldMatches(row.getFieldMatches()),
                        row.getConditionExpr() == null ? null : RuleExpression.parse(row.getConditionExpr())),
                parseRounds(row.getApprovers()));
    }

    private Map<String, String> parseFieldMatches(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, FIELD_MATCHES_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse field_matches JSON", e);
        }
    }

    private List<List<ApproverRef>> parseRounds(String json) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, APPROVERS_TYPE);
            java.util.TreeMap<Integer, List<ApproverRef>> byRound = new java.util.TreeMap<>();
            for (Map<String, Object> entry : raw) {
                int round = entry.get("round") instanceof Number n ? n.intValue() : 1;
                byRound.computeIfAbsent(round, r -> new java.util.ArrayList<>())
                        .add(new ApproverRef(String.valueOf(entry.get("type")), String.valueOf(entry.get("id"))));
            }
            return List.copyOf(byRound.values());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse approvers JSON", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize rule JSON", e);
        }
    }
}
