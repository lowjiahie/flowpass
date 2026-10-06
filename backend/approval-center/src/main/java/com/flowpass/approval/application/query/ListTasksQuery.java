package com.flowpass.approval.application.query;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

/**
 * Query for paging approval tasks of a tenant.
 *
 * @param tenantId   tenant scope
 * @param instanceId optional instance filter (null = all tasks of the tenant)
 * @param status     optional status filter ({@code PENDING}/{@code CLAIMED}/... , null = all)
 * @param assignedTo optional: only tasks assigned to this user or to a role they hold ("my tasks")
 * @param cursor     last-seen task id (exclusive lower bound, null = first page)
 * @param limit      page size
 */
public record ListTasksQuery(TenantId tenantId, String instanceId, String status, ApproverRef assignedTo,
                             String cursor, int limit) {

    /** All tasks of the tenant (no assignee filter). */
    public ListTasksQuery(TenantId tenantId, String instanceId, String status, String cursor, int limit) {
        this(tenantId, instanceId, status, null, cursor, limit);
    }
}
