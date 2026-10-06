package com.flowpass.approval.domain.decision;

import com.flowpass.approval.domain.common.TenantId;

import java.util.List;

/**
 * Outbound port for reading and storing approval-matrix rules.
 *
 * <p>Lives in {@code domain} so the decision adapter can resolve first-round approvers without knowing
 * how rules are stored. The adapter in {@code infrastructure} maps to the {@code decision_rule} table.</p>
 */
public interface DecisionRuleRepository {

    /** Rules for a tenant + template key, ordered by ascending priority. */
    List<DecisionRule> findByTemplate(TenantId tenantId, String templateKey);

    /** All rules for a tenant (admin listing), ordered by template key then priority. */
    List<DecisionRule> findAll(TenantId tenantId);

    /** Persist a new rule. */
    void save(DecisionRule rule);
}
