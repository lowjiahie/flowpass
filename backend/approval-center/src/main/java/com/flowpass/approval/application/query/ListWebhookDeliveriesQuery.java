package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;

/** Read query for pageable webhook deliveries (admin/ops view). */
public record ListWebhookDeliveriesQuery(TenantId tenantId, String status, String cursor, int limit) {
}
