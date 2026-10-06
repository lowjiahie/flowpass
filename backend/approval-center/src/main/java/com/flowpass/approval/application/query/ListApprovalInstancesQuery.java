package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.TenantId;

/**
 * Query for paging approval instances of a tenant.
 *
 * @param tenantId tenant scope
 * @param status   optional status filter (null = all)
 * @param cursor   last-seen instance id (exclusive lower bound, null = first page)
 * @param limit    page size
 */
public record ListApprovalInstancesQuery(TenantId tenantId, String status, String cursor, int limit) {
}
