package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis mapper for {@code decision_rule}. Explicit SQL keeps the tenant filter visible. Mappers are
 * infrastructure only — never referenced by domain.
 */
@Mapper
public interface DecisionRuleMapper {

    @Insert("""
            INSERT INTO decision_rule
                (id, tenant_id, template_key, priority, amount_min, amount_max, field_matches, approvers)
            VALUES
                (#{id}, #{tenantId}, #{templateKey}, #{priority}, #{amountMin}, #{amountMax},
                 #{fieldMatches}::jsonb, #{approvers}::jsonb)
            """)
    void insert(DecisionRuleRow row);

    @Select("""
            SELECT id, tenant_id, template_key, priority, amount_min, amount_max, field_matches, approvers
            FROM decision_rule
            WHERE tenant_id = #{tenantId} AND template_key = #{templateKey}
            ORDER BY priority ASC, id ASC
            """)
    List<DecisionRuleRow> findByTemplate(@Param("tenantId") String tenantId, @Param("templateKey") String templateKey);

    @Select("""
            SELECT id, tenant_id, template_key, priority, amount_min, amount_max, field_matches, approvers
            FROM decision_rule
            WHERE tenant_id = #{tenantId}
            ORDER BY template_key ASC, priority ASC, id ASC
            """)
    List<DecisionRuleRow> findAll(@Param("tenantId") String tenantId);
}
