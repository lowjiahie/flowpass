package com.flowpass.approval.application.port;

import com.flowpass.approval.domain.common.TenantId;

import java.util.List;
import java.util.Map;

/**
 * Outbound port driving the process engine behind an approval run.
 *
 * <p>The engine is an infrastructure concern, never a domain model (charter §4.3). It is the source of
 * truth for <em>routing</em>: which user tasks exist, and when the run is over and how. Every call returns
 * a {@link WorkflowStep} describing what the engine did, so the application layer never guesses it (no
 * "no pending tasks left" heuristics). Engine ids stay inside the adapter; the opaque
 * {@link WorkflowTask#ref()} is only ever handed back to the same port.</p>
 */
public interface WorkflowEnginePort {

    /**
     * Start a process instance for a newly created approval instance.
     *
     * @param tenantId          tenant scope (engine deployments and runs are tenant-scoped)
     * @param instanceId        platform instance id (never an engine id)
     * @param definitionKey     published template key to run
     * @param definitionVersion pinned definition version
     * @param variables         form / rule variables snapshot
     * @param approvers         resolved first-round approvers (empty = nobody resolved, manual handling)
     * @return the tasks the engine opened, and the outcome if it already finished
     */
    WorkflowStep start(
            TenantId tenantId,
            String instanceId,
            String definitionKey,
            Integer definitionVersion,
            Map<String, Object> variables,
            List<CandidateApprover> approvers);

    /**
     * Complete one engine user task with a decision, advancing the run.
     *
     * @param tenantId   tenant scope (the mapping is tenant-scoped)
     * @param instanceId platform instance id (never an engine id)
     * @param taskRef    the {@link WorkflowTask#ref()} of the task being completed
     * @param decision   {@code APPROVED} or {@code REJECTED}
     * @return the tasks newly opened by this step, and the outcome if the run finished
     * @throws com.flowpass.approval.application.common.BusinessException with {@code CONFLICT} when a
     *         concurrent step on the same run won the race (the caller may retry)
     * @throws IllegalStateException if the task is not active for the instance
     */
    WorkflowStep completeTask(TenantId tenantId, String instanceId, String taskRef, WorkflowOutcome decision);

    /**
     * Terminate a running process instance, removing its remaining active tasks and stopping the
     * engine from advancing. Called by the withdraw use case, which ends the instance early (it does
     * not walk the workflow to its end node).
     *
     * @param tenantId   tenant scope (the mapping is tenant-scoped)
     * @param instanceId platform instance id (never an engine id)
     * @throws IllegalStateException if no workflow ref is found for the instance
     */
    void terminate(TenantId tenantId, String instanceId);
}
