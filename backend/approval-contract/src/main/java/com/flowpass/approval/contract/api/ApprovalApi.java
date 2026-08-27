package com.flowpass.approval.contract.api;

import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.contract.dto.CreateApprovalRequest;

/**
 * Internal service contract for the approval core.
 *
 * <p>This is the Dubbo Triple contract (RFC-style) used between {@code approval-gateway} and
 * {@code approval-center}. External systems keep using REST over /v1; this interface is the
 * in-mesh contract. Backward compatible by design — do not change signatures without a new version.</p>
 */
public interface ApprovalApi {

    /**
     * Start a new approval instance for a tenant.
     *
     * @param tenantId trusted tenant id resolved and verified server-side, never taken from the body
     * @param request  validated create request
     * @return created instance read model
     */
    ApprovalInstanceDTO createInstance(String tenantId, CreateApprovalRequest request);

    /**
     * Fetch a single instance by id, tenant-scoped.
     *
     * @param tenantId   trusted tenant id
     * @param instanceId platform instance id
     * @return instance read model, or null when not found / not visible to the tenant
     */
    ApprovalInstanceDTO getInstance(String tenantId, String instanceId);
}
