package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MyBatis mapper for {@code sla_policy}. Every statement is tenant-scoped. */
@Mapper
public interface SlaPolicyMapper {

    @Select("""
            SELECT tenant_id, template_key, due_after_hours, remind_before_hours, on_overdue, escalate_to_user
            FROM sla_policy
            WHERE tenant_id = #{tenantId} AND template_key = #{templateKey}
            """)
    SlaPolicyRow find(@Param("tenantId") String tenantId, @Param("templateKey") String templateKey);

    @Insert("""
            INSERT INTO sla_policy (tenant_id, template_key, due_after_hours, remind_before_hours, on_overdue, escalate_to_user)
            VALUES (#{tenantId}, #{templateKey}, #{dueAfterHours}, #{remindBeforeHours}, #{onOverdue}, #{escalateToUser})
            ON CONFLICT (tenant_id, template_key) DO UPDATE
               SET due_after_hours = EXCLUDED.due_after_hours,
                   remind_before_hours = EXCLUDED.remind_before_hours,
                   on_overdue = EXCLUDED.on_overdue,
                   escalate_to_user = EXCLUDED.escalate_to_user,
                   updated_at = now()
            """)
    void upsert(SlaPolicyRow row);

    @Delete("""
            DELETE FROM sla_policy WHERE tenant_id = #{tenantId} AND template_key = #{templateKey}
            """)
    int delete(@Param("tenantId") String tenantId, @Param("templateKey") String templateKey);
}
