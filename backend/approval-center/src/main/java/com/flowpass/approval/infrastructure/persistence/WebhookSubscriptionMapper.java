package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis mapper for {@code webhook_subscription}. Read-only for now — the dispatcher resolves the
 * destinations for a tenant + event type. Mappers are infrastructure only — never referenced by domain.
 */
@Mapper
public interface WebhookSubscriptionMapper {

    @Select("""
            SELECT id, tenant_id, event_type, endpoint, enabled
            FROM webhook_subscription
            WHERE tenant_id = #{tenantId} AND event_type = #{eventType} AND enabled = true
            ORDER BY id ASC
            """)
    List<WebhookSubscriptionRow> findEnabled(@Param("tenantId") String tenantId, @Param("eventType") String eventType);
}
