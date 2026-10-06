package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MyBatis mapper for {@code approval_workflow_ref}. */
@Mapper
public interface WorkflowRefMapper {

    @Insert("""
            INSERT INTO approval_workflow_ref
                (instance_id, tenant_id, definition_key, definition_version, process_instance_id)
            VALUES
                (#{instanceId}, #{tenantId}, #{definitionKey}, #{definitionVersion}, #{processInstanceId})
            """)
    void insert(WorkflowRefRow row);

    @Select("""
            SELECT instance_id, tenant_id, definition_key, definition_version, process_instance_id
            FROM approval_workflow_ref
            WHERE tenant_id = #{tenantId} AND instance_id = #{instanceId}
            """)
    WorkflowRefRow findByInstanceId(@Param("tenantId") String tenantId, @Param("instanceId") String instanceId);

    /** Same row, locked until the surrounding transaction ends — serialises the steps of one run. */
    @Select("""
            SELECT instance_id, tenant_id, definition_key, definition_version, process_instance_id
            FROM approval_workflow_ref
            WHERE tenant_id = #{tenantId} AND instance_id = #{instanceId}
            FOR UPDATE
            """)
    WorkflowRefRow findByInstanceIdForUpdate(@Param("tenantId") String tenantId, @Param("instanceId") String instanceId);
}
