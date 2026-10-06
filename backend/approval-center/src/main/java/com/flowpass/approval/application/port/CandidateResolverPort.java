package com.flowpass.approval.application.port;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

import java.util.Map;

/**
 * Outbound port resolving the first-round approvers for a started approval.
 *
 * <p>Pinning who approves is a decision-context concern. This port keeps the start use case free of the
 * approval-matrix / rule engine; the adapter implements the actual resolution strategy: it selects the
 * tenant's matching rule and turns its approver entries (user, role, the initiator's manager, ...) into
 * concrete principals.</p>
 */
public interface CandidateResolverPort {

    /**
     * Resolve first-round approvers for the instance.
     *
     * @param initiator who starts the request (manager / department entries are relative to them)
     * @return the resolved approvers, or the reasons resolution failed; nothing when no rule matches
     */
    ApproverResolution resolve(TenantId tenantId, String templateKey, ApproverRef initiator,
                               Map<String, Object> variables);
}
