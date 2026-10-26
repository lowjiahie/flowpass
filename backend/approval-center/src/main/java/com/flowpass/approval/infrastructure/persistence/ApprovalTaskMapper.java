package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/** MyBatis mapper for {@code approval_task}. */
@Mapper
public interface ApprovalTaskMapper {

    @Insert("""
            INSERT INTO approval_task
                (id, tenant_id, instance_id, name, assignee_type, assignee_id, status, version, node_key, workflow_task_ref,
                 due_at, remind_at)
            VALUES
                (#{id}, #{tenantId}, #{instanceId}, #{name}, #{assigneeType}, #{assigneeId}, #{status}, #{version},
                 #{nodeKey}, #{workflowTaskRef}, #{dueAt}, #{remindAt})
            """)
    void insert(ApprovalTaskRow row);

    @Select("""
            SELECT id, tenant_id, instance_id, name, assignee_type, assignee_id, status, version, node_key, workflow_task_ref,
                   due_at, remind_at, reminded_at, overdue_handled_at
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

    @Update("""
            UPDATE approval_task
            SET assignee_type = #{assigneeType}, assignee_id = #{assigneeId}, version = #{version}, updated_at = now()
            WHERE id = #{id} AND version = #{version} - 1
            """)
    int updateAssignee(@Param("id") String id, @Param("assigneeType") String assigneeType,
                       @Param("assigneeId") String assigneeId, @Param("version") long version);

    @Select("""
            SELECT count(*) FROM approval_task
            WHERE tenant_id = #{tenantId} AND instance_id = #{instanceId}
              AND assignee_type = 'USER' AND assignee_id = #{userId}
            """)
    long countUserTasks(@Param("tenantId") String tenantId, @Param("instanceId") String instanceId,
                        @Param("userId") String userId);

    @Select("""
            SELECT id, tenant_id, instance_id, name, assignee_type, assignee_id, status, version, node_key, workflow_task_ref,
                   due_at, remind_at, reminded_at, overdue_handled_at
            FROM approval_task
            WHERE tenant_id = #{tenantId} AND instance_id = #{instanceId}
              AND status IN ('PENDING', 'CLAIMED')
            ORDER BY id ASC
            """)
    List<ApprovalTaskRow> findOpenByInstance(
            @Param("tenantId") String tenantId, @Param("instanceId") String instanceId);

    @Select("""
            <script>
            SELECT id, tenant_id, instance_id, name, assignee_type, assignee_id, status, version, node_key, workflow_task_ref,
                   due_at, remind_at, reminded_at, overdue_handled_at
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

    // ---- deadline worker (system view across tenants; every task is then handled within its own tenant) ----

    @Select("""
            SELECT tenant_id, id FROM approval_task
            WHERE status IN ('PENDING', 'CLAIMED') AND remind_at IS NOT NULL AND remind_at <= #{now}
              AND reminded_at IS NULL
            ORDER BY remind_at ASC
            LIMIT #{limit}
            """)
    List<ApprovalTaskRow> findDueForReminder(@Param("now") Instant now, @Param("limit") int limit);

    @Select("""
            SELECT tenant_id, id FROM approval_task
            WHERE status IN ('PENDING', 'CLAIMED') AND due_at IS NOT NULL AND due_at <= #{now}
              AND overdue_handled_at IS NULL
            ORDER BY due_at ASC
            LIMIT #{limit}
            """)
    List<ApprovalTaskRow> findOverdue(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE approval_task SET reminded_at = #{now}
            WHERE tenant_id = #{tenantId} AND id = #{id} AND reminded_at IS NULL
              AND status IN ('PENDING', 'CLAIMED') AND remind_at IS NOT NULL AND remind_at <= #{now}
            """)
    int claimReminder(@Param("tenantId") String tenantId, @Param("id") String id, @Param("now") Instant now);

    @Update("""
            UPDATE approval_task SET overdue_handled_at = #{now}
            WHERE tenant_id = #{tenantId} AND id = #{id} AND overdue_handled_at IS NULL
              AND status IN ('PENDING', 'CLAIMED') AND due_at IS NOT NULL AND due_at <= #{now}
            """)
    int claimOverdue(@Param("tenantId") String tenantId, @Param("id") String id, @Param("now") Instant now);
}
