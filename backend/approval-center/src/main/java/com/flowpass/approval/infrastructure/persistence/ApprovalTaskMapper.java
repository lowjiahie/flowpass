package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** MyBatis mapper for {@code approval_task}. */
@Mapper
public interface ApprovalTaskMapper {

    @Insert("""
            INSERT INTO approval_task
                (id, tenant_id, instance_id, name, assignee_type, assignee_id, status, version, node_key, workflow_task_ref)
            VALUES
                (#{id}, #{tenantId}, #{instanceId}, #{name}, #{assigneeType}, #{assigneeId}, #{status}, #{version},
                 #{nodeKey}, #{workflowTaskRef})
            """)
    void insert(ApprovalTaskRow row);

    @Select("""
            SELECT id, tenant_id, instance_id, name, assignee_type, assignee_id, status, version, node_key, workflow_task_ref
            FROM approval_task
            WHERE tenant_id = #{tenantId} AND id = #{taskId}
            """)
    ApprovalTaskRow findById(@Param("tenantId") String tenantId, @Param("taskId") String taskId);

    @Update("""
            UPDATE approval_task
            SET status = #{status}, version = #{version}, updated_at = now()
            WHERE id = #{id} AND version = #{version} - 1
            """)
    int updateStatus(@Param("id") String id, @Param("status") String status, @Param("version") long version);

    @Select("""
            SELECT id, tenant_id, instance_id, name, assignee_type, assignee_id, status, version, node_key, workflow_task_ref
            FROM approval_task
            WHERE tenant_id = #{tenantId} AND instance_id = #{instanceId}
              AND status IN ('PENDING', 'CLAIMED')
            ORDER BY id ASC
            """)
    List<ApprovalTaskRow> findOpenByInstance(
            @Param("tenantId") String tenantId, @Param("instanceId") String instanceId);

    @Select("""
            <script>
            SELECT id, tenant_id, instance_id, name, assignee_type, assignee_id, status, version, node_key, workflow_task_ref
            FROM approval_task
            WHERE tenant_id = #{tenantId}
            <if test="instanceId != null"> AND instance_id = #{instanceId}</if>
            <if test="status != null"> AND status = #{status}</if>
            <if test="userId != null">
              AND ((assignee_type = 'USER' AND assignee_id = #{userId})
              <if test="roles != null and !roles.isEmpty()">
                OR (assignee_type = 'ROLE' AND assignee_id IN
                    <foreach collection="roles" item="role" open="(" separator="," close=")">#{role}</foreach>)
              </if>
              )
            </if>
            <if test="cursor != null"> AND id &gt; #{cursor}</if>
            ORDER BY id ASC
            LIMIT #{limit}
            </script>
            """)
    List<ApprovalTaskRow> findPage(
            @Param("tenantId") String tenantId,
            @Param("instanceId") String instanceId,
            @Param("status") String status,
            @Param("userId") String userId,
            @Param("roles") List<String> roles,
            @Param("cursor") String cursor,
            @Param("limit") int limit);
}
