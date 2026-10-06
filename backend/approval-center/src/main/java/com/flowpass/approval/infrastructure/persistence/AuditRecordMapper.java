package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MyBatis mapper for {@code approval_action} (append-only audit ledger). */
@Mapper
public interface AuditRecordMapper {

    @Insert("""
            INSERT INTO approval_action
                (id, tenant_id, instance_id, task_id, actor_type, actor_id, action, comment, created_at)
            VALUES
                (#{id}, #{tenantId}, #{instanceId}, #{taskId}, #{actorType}, #{actorId}, #{action}, #{comment}, #{createdAt})
            """)
    void insert(AuditRecordRow row);

    @Select("""
            <script>
            SELECT id, tenant_id, instance_id, task_id, actor_type, actor_id, action, comment, created_at
            FROM approval_action
            WHERE tenant_id = #{tenantId}
            <if test="instanceId != null"> AND instance_id = #{instanceId}</if>
            <if test="action != null"> AND action = #{action}</if>
            <if test="cursor != null"> AND id &gt; #{cursor}</if>
            ORDER BY id ASC
            LIMIT #{limit}
            </script>
            """)
    List<AuditRecordRow> findPage(
            @Param("tenantId") String tenantId,
            @Param("instanceId") String instanceId,
            @Param("action") String action,
            @Param("cursor") String cursor,
            @Param("limit") int limit);
}
