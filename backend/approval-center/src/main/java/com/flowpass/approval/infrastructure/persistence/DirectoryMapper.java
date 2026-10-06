package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MyBatis mapper for the temporary organisation directory tables. Every query is tenant-scoped. */
@Mapper
public interface DirectoryMapper {

    @Select("""
            SELECT manager_id FROM directory_user
            WHERE tenant_id = #{tenantId} AND user_id = #{userId}
            """)
    String managerOf(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("""
            SELECT d.head_user_id
            FROM directory_user u
            JOIN directory_department d
              ON d.tenant_id = u.tenant_id AND d.department_id = u.department_id
            WHERE u.tenant_id = #{tenantId} AND u.user_id = #{userId}
            """)
    String departmentHeadOf(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("""
            SELECT role_name FROM directory_role_member
            WHERE tenant_id = #{tenantId} AND user_id = #{userId}
            """)
    List<String> rolesOf(@Param("tenantId") String tenantId, @Param("userId") String userId);
}
