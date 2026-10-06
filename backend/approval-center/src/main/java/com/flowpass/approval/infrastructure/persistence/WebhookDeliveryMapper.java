package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * MyBatis mapper for {@code webhook_delivery}. Enqueues {@code PENDING} rows, then the webhook dispatcher
 * reads due rows and updates them after each attempt. Mappers are infrastructure only — never referenced
 * by domain.
 */
@Mapper
public interface WebhookDeliveryMapper {

    @Insert("""
            <script>
            INSERT INTO webhook_delivery (id, event_id, tenant_id, endpoint, attempt, status, created_at)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.id}, #{r.eventId}, #{r.tenantId}, #{r.endpoint},
                 #{r.attempt}, #{r.status}, #{r.createdAt})
            </foreach>
            </script>
            """)
    int insertAll(@Param("rows") List<WebhookDeliveryRow> rows);

    @Select("""
            SELECT id, event_id, tenant_id, endpoint, attempt, status, next_retry_at, created_at
            FROM webhook_delivery
            WHERE status = 'PENDING'
              AND (next_retry_at IS NULL OR next_retry_at <= now())
            ORDER BY created_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<WebhookDeliveryRow> findDue(@Param("limit") int limit);

    @Update("""
            UPDATE webhook_delivery
            SET attempt = #{attempt}, status = #{status}, next_retry_at = #{nextRetryAt}
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") String id, @Param("attempt") int attempt,
                     @Param("status") String status, @Param("nextRetryAt") Instant nextRetryAt);

    @Select("""
            <script>
            SELECT id, event_id, tenant_id, endpoint, attempt, status, next_retry_at, created_at
            FROM webhook_delivery
            WHERE tenant_id = #{tenantId}
            <if test="status != null"> AND status = #{status}</if>
            <if test="cursor != null"> AND id &gt; #{cursor}</if>
            ORDER BY id ASC
            LIMIT #{limit}
            </script>
            """)
    List<WebhookDeliveryRow> findPage(@Param("tenantId") String tenantId, @Param("status") String status,
                                      @Param("cursor") String cursor, @Param("limit") int limit);
}
