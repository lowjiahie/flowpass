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

    /** The most approval rounds a definition can use: round k reads the variables {@code approversK} / {@code hasApproversK}. */
    int MAX_ROUNDS = 5;

    /**
     * Start a process instance for a newly created approval instance.
     *
     * @param tenantId          tenant scope (engine deployments and runs are tenant-scoped)
     * @param instanceId        platform instance id (never an engine id)
     * @param definitionKey     published template key to run
     * @param definitionVersion pinned definition version
     * @param variables         form / rule variables snapshot
     * @param rounds            the resolved approval chain: round 1, round 2, ... (empty = nobody resolved,
     *                          manual handling). At most {@code MAX_ROUNDS}; the adapter drops empty rounds.
     * @return the tasks the engine opened, and the outcome if it already finished
     */
    WorkflowStep start(
            TenantId tenantId,
            String instanceId,
            String definitionKey,
            Integer definitionVersion,
            Map<String, Object> variables,
            List<List<CandidateApprover>> rounds);

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
     * Hand an open engine task to another user, without completing it.
     *
     * @param taskRef  the {@link WorkflowTask#ref()} of the task
     * @param assignee the new assignee ({@code USER})
     * @throws IllegalStateException if the task is not active for the instance
     */
    void reassign(TenantId tenantId, String instanceId, String taskRef, CandidateApprover assignee);

    /**
     * Add one more approver to the approval round the task belongs to (parallel add-sign): the round now also
     * needs the new approver's approval before it can complete.
     *
     * @return the task opened for the new approver
     * @throws IllegalStateException if the task is not active for the instance, or its step cannot take more
     *         approvers (e.g. a manual-review task)
     */
    WorkflowStep addApprover(TenantId tenantId, String instanceId, String taskRef, CandidateApprover approver);

    /**
     * Send the run back to the previous approval round: the task's round ends without a decision (the other
     * tasks of the round are removed), and the previous round opens again for its approvers.
     *
     * @return the tasks the previous round opened again
     * @throws IllegalStateException if there is no earlier round, or the definition does not route a return
     *         (nothing is changed in that case)
     */
    WorkflowStep returnToPreviousRound(TenantId tenantId, String instanceId, String taskRef);

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
