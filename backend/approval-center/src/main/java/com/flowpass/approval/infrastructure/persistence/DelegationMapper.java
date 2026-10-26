package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/** MyBatis mapper for {@code delegation}. Every statement is tenant-scoped. */
@Mapper
public interface DelegationMapper {

    @Insert("""
            INSERT INTO delegation (id, tenant_id, delegator_id, delegate_id, starts_at, ends_at)
            VALUES (#{id}, #{tenantId}, #{delegatorId}, #{delegateId}, #{startsAt}, #{endsAt})
            """)
    void insert(DelegationRow row);

    @Update("""
            UPDATE delegation SET revoked_at = #{revokedAt}
            WHERE tenant_id = #{tenantId} AND id = #{id} AND revoked_at IS NULL
            """)
    int revoke(@Param("tenantId") String tenantId, @Param("id") String id, @Param("revokedAt") Instant revokedAt);

    @Select("""
            SELECT id, tenant_id, delegator_id, delegate_id, starts_at, ends_at, revoked_at
            FROM delegation WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    DelegationRow findById(@Param("tenantId") String tenantId, @Param("id") String id);

    @Select("""
            SELECT id, tenant_id, delegator_id, delegate_id, starts_at, ends_at, revoked_at
            FROM delegation
            WHERE tenant_id = #{tenantId} AND delegator_id = #{delegatorId} AND revoked_at IS NULL
              AND starts_at <= #{at} AND ends_at > #{at}
            ORDER BY starts_at DESC
            LIMIT 1
            """)
    DelegationRow findActive(@Param("tenantId") String tenantId, @Param("delegatorId") String delegatorId,
                             @Param("at") Instant at);

    @Select("""
            SELECT id, tenant_id, delegator_id, delegate_id, starts_at, ends_at, revoked_at
            FROM delegation
            WHERE tenant_id = #{tenantId} AND delegator_id = #{delegatorId} AND revoked_at IS NULL AND ends_at > #{now}
            ORDER BY starts_at ASC
            """)
    List<DelegationRow> findCurrentAndFuture(@Param("tenantId") String tenantId,
                                             @Param("delegatorId") String delegatorId, @Param("now") Instant now);
}
