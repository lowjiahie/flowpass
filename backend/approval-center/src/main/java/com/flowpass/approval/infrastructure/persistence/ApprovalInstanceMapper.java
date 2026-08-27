package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis mapper for {@code approval_instance}. Explicit SQL keeps the tenant filter and optimistic
 * version check visible and auditable. Mappers are infrastructure only — never referenced by domain.
 */
@Mapper
public interface ApprovalInstanceMapper {

    @Select("""
            SELECT id, tenant_id, application_id, business_key, template_key,
                   initiator_type, initiator_id, amount, currency,
                   definition_version, status, version
            FROM approval_instance
            WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    ApprovalInstanceRow findById(@Param("tenantId") String tenantId, @Param("id") String id);

    @Insert("""
            INSERT INTO approval_instance
                (id, tenant_id, application_id, business_key, template_key,
                 initiator_type, initiator_id, amount, currency,
                 definition_version, status, version, created_at, updated_at)
            VALUES
                (#{id}, #{tenantId}, #{applicationId}, #{businessKey}, #{templateKey},
                 #{initiatorType}, #{initiatorId}, #{amount}, #{currency},
                 #{definitionVersion}, #{status}, #{version}, now(), now())
            """)
    int insert(ApprovalInstanceRow row);

    @Update("""
            UPDATE approval_instance
            SET status = #{status}, version = #{version}, updated_at = now()
            WHERE id = #{id} AND version = #{version} - 1
            """)
    int updateStatus(@Param("id") String id, @Param("status") String status, @Param("version") long version);
}
