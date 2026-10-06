package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * MyBatis mapper for {@code outbox_event}. Appends {@code PENDING} rows in the same local transaction as
 * the aggregate write so the business change and its events commit atomically; the dispatch side later
 * reads pending rows and flips them to {@code DISPATCHED}. Mappers are infrastructure only.
 */
@Mapper
public interface OutboxMapper {

    @Insert("""
            INSERT INTO outbox_event
                (id, event_id, tenant_id, aggregate_id, aggregate_type, type, payload, publish_status, created_at)
            VALUES
                (#{id}, #{eventId}, #{tenantId}, #{aggregateId}, #{aggregateType}, #{type},
                 #{payload}::jsonb, 'PENDING', now())
            """)
    int insert(OutboxEventRow row);

    @Select("""
            SELECT event_id, tenant_id, type, payload
            FROM outbox_event
            WHERE publish_status = 'PENDING'
            ORDER BY created_at ASC, event_id ASC
            LIMIT #{limit}
            """)
    List<OutboxEventRow> findPending(@Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET publish_status = 'DISPATCHED', published_at = now()
            WHERE event_id = #{eventId} AND publish_status = 'PENDING'
            """)
    int markDispatched(@Param("eventId") String eventId);

    @Select("""
            SELECT payload
            FROM outbox_event
            WHERE event_id = #{eventId}
            """)
    String findPayload(@Param("eventId") String eventId);
}
