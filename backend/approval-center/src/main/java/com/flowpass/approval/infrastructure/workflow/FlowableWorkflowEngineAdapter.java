package com.flowpass.approval.infrastructure.workflow;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowOutcome;
import com.flowpass.approval.application.port.WorkflowStep;
import com.flowpass.approval.application.port.WorkflowTask;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.DefinitionRepository;
import com.flowpass.approval.infrastructure.persistence.WorkflowRefRow;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real Flowable-backed {@link WorkflowEnginePort}.
 *
 * <p>The adapter owns every engine-specific concern: it deploys the stored BPMN of a published definition
 * version <em>per tenant</em> (idempotent by deployment key), starts a process instance of exactly that
 * version with the platform instance id as the business key, and keeps the {@code processInstanceId} in a tenant-scoped
 * {@code approval_workflow_ref} mapping. Engine ids are never exposed as public ids and Flowable is never
 * imported by the {@code domain} layer.</p>
 *
 * <p>The engine is the source of truth for routing. After every call the adapter reports a
 * {@link WorkflowStep}: the user tasks newly opened and, if the run reached an end event, the outcome — read
 * from <em>which</em> end event ({@value #END_REJECTED} / {@value #END_APPROVED}) the model reached.</p>
 */
@Component
public class FlowableWorkflowEngineAdapter implements WorkflowEnginePort {

    static final String VAR_APPROVERS = "approvers";
    static final String VAR_HAS_APPROVERS = "hasApprovers";
    static final String VAR_REJECTED = "rejected";
    static final String VAR_RETURNED = "returned";
    static final String END_REJECTED = "endRejected";
    static final String END_APPROVED = "endApproved";
    private static final String ASSIGNEE_SEPARATOR = ":";

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final DefinitionRepository definitionRepository;
    private final WorkflowRefRepository workflowRefRepository;

    public FlowableWorkflowEngineAdapter(
            RepositoryService repositoryService,
            RuntimeService runtimeService,
            TaskService taskService,
            HistoryService historyService,
            DefinitionRepository definitionRepository,
            WorkflowRefRepository workflowRefRepository) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.definitionRepository = definitionRepository;
        this.workflowRefRepository = workflowRefRepository;
    }

    @Override
    public WorkflowStep start(TenantId tenantId, String instanceId, String definitionKey,
                              Integer definitionVersion, Map<String, Object> variables,
                              List<List<CandidateApprover>> rounds) {
        String processDefinitionId = ensureDeployed(tenantId, definitionKey, definitionVersion);

        Map<String, Object> processVariables = new HashMap<>(variables == null ? Map.of() : variables);
        // Engine-owned variables win over same-named form variables.
        // Round k (1-based) is exposed to the definition as `approvers` (k = 1) / `approversK` and `hasApprovers` /
        // `hasApproversK`; every round up to the maximum is always defined, so expressions never hit an unknown name.
        List<List<CandidateApprover>> chain = rounds.stream().filter(round -> !round.isEmpty()).toList();
        if (chain.size() > MAX_ROUNDS) {
            throw new IllegalArgumentException("an approval chain can have at most " + MAX_ROUNDS + " rounds");
        }
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            List<CandidateApprover> approvers = round <= chain.size() ? chain.get(round - 1) : List.of();
            processVariables.put(round == 1 ? VAR_APPROVERS : VAR_APPROVERS + round, new ArrayList<>(approvers.stream()
                    .map(a -> a.type() + ASSIGNEE_SEPARATOR + a.id()).toList()));
            processVariables.put(round == 1 ? VAR_HAS_APPROVERS : VAR_HAS_APPROVERS + round, !approvers.isEmpty());
        }
        processVariables.put(VAR_REJECTED, false);
        processVariables.put(VAR_RETURNED, false);

        // Start by definition id, not key: the instance runs exactly the version it is pinned to, even
        // after newer versions of the same key are deployed.
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(
                processDefinitionId, instanceId, processVariables);
        workflowRefRepository.save(tenantId.value(), instanceId, definitionKey, definitionVersion,
                processInstance.getId());
        return stepAfter(processInstance.getId(), Set.of());
    }

    @Override
    @Transactional
    public WorkflowStep completeTask(TenantId tenantId, String instanceId, String taskRef,
                                     WorkflowOutcome decision) {
        // Serialise the steps of one run (see WorkflowRefRepository#findByInstanceIdForUpdate).
        WorkflowRefRow ref = workflowRefRepository.findByInstanceIdForUpdate(tenantId.value(), instanceId)
                .orElseThrow(() -> new IllegalStateException("No workflow ref for instance " + instanceId));
        String processInstanceId = ref.getProcessInstanceId();

        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).taskId(taskRef)
                .singleResult();
        if (task == null) {
            throw new IllegalStateException("No active task " + taskRef + " for process instance " + processInstanceId);
        }

        Set<String> openBefore = new HashSet<>();
        taskService.createTaskQuery().processInstanceId(processInstanceId).list()
                .forEach(t -> openBefore.add(t.getId()));

        try {
            // A rejection flags the run: the step's completion condition then ends it at once and the engine
            // removes the sibling tasks; the decision gateway routes to the rejected end event.
            Map<String, Object> variables = decision == WorkflowOutcome.REJECTED
                    ? Map.of(VAR_REJECTED, true)
                    : Map.of();
            taskService.complete(taskRef, variables);
        } catch (FlowableOptimisticLockingException e) {
            // Defence in depth: steps of one run are serialised by the row lock above, but if the engine still
            // reports a clash the caller is told to retry — never a raw engine error.
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "Concurrent workflow step on instance " + instanceId + "; retry");
        }
        return stepAfter(processInstanceId, openBefore);
    }

    @Override
    @Transactional
    public void reassign(TenantId tenantId, String instanceId, String taskRef, CandidateApprover assignee) {
        String processInstanceId = lockedProcessInstance(tenantId, instanceId);
        requireActiveTask(processInstanceId, taskRef);
        taskService.setAssignee(taskRef, assignee.type() + ASSIGNEE_SEPARATOR + assignee.id());
    }

    @Override
    @Transactional
    public WorkflowStep addApprover(TenantId tenantId, String instanceId, String taskRef, CandidateApprover approver) {
        String processInstanceId = lockedProcessInstance(tenantId, instanceId);
        Task task = requireActiveTask(processInstanceId, taskRef);
        Set<String> openBefore = openTaskIds(processInstanceId);
        try {
            // A new instance of the same parallel step: the round now also waits for this approver.
            runtimeService.addMultiInstanceExecution(task.getTaskDefinitionKey(), processInstanceId,
                    Map.of("approver", approver.type() + ASSIGNEE_SEPARATOR + approver.id()));
        } catch (FlowableOptimisticLockingException e) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "Concurrent workflow step on instance " + instanceId + "; retry");
        } catch (FlowableException e) {
            throw new IllegalStateException("The step '" + task.getTaskDefinitionKey()
                    + "' cannot take more approvers", e);
        }
        return stepAfter(processInstanceId, openBefore);
    }

    @Override
    @Transactional
    public WorkflowStep returnToPreviousRound(TenantId tenantId, String instanceId, String taskRef) {
        String processInstanceId = lockedProcessInstance(tenantId, instanceId);
        Task task = requireActiveTask(processInstanceId, taskRef);
        String currentNode = task.getTaskDefinitionKey();

        // The rounds already gone through: every user-task node finished before, except the one being returned from.
        Set<String> earlier = new HashSet<>();
        historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId)
                .activityType("userTask").finished().list()
                .forEach(activity -> earlier.add(activity.getActivityId()));
        earlier.remove(currentNode);
        if (earlier.isEmpty()) {
            throw new IllegalStateException("There is no earlier round to return to");
        }

        Set<String> openBefore = openTaskIds(processInstanceId);
        try {
            // Like a rejection, but routed backwards by the definition: the round ends at once and the gateway
            // sends the run to the previous round, which opens again for its approvers.
            runtimeService.setVariable(processInstanceId, VAR_RETURNED, true);
            taskService.complete(taskRef);
            if (isRunning(processInstanceId)) {
                runtimeService.setVariable(processInstanceId, VAR_RETURNED, false);
            }
        } catch (FlowableOptimisticLockingException e) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "Concurrent workflow step on instance " + instanceId + "; retry");
        }

        WorkflowStep step = stepAfter(processInstanceId, openBefore);
        boolean rewound = !step.finished() && !step.newTasks().isEmpty()
                && step.newTasks().stream().allMatch(t -> earlier.contains(t.nodeKey()));
        if (!rewound) {
            // The definition did not route the return backwards: refuse, and undo everything (the caller's
            // transaction rolls back, including the completion above).
            throw new IllegalStateException("This definition does not support returning to an earlier round");
        }
        return step;
    }

    private String lockedProcessInstance(TenantId tenantId, String instanceId) {
        return workflowRefRepository.findByInstanceIdForUpdate(tenantId.value(), instanceId)
                .orElseThrow(() -> new IllegalStateException("No workflow ref for instance " + instanceId))
                .getProcessInstanceId();
    }

    private Task requireActiveTask(String processInstanceId, String taskRef) {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).taskId(taskRef).singleResult();
        if (task == null) {
            throw new IllegalStateException("No active task " + taskRef + " for process instance " + processInstanceId);
        }
        return task;
    }

    private Set<String> openTaskIds(String processInstanceId) {
        Set<String> ids = new HashSet<>();
        taskService.createTaskQuery().processInstanceId(processInstanceId).list().forEach(t -> ids.add(t.getId()));
        return ids;
    }

    @Override
    @Transactional
    public void terminate(TenantId tenantId, String instanceId) {
        WorkflowRefRow ref = workflowRefRepository.findByInstanceIdForUpdate(tenantId.value(), instanceId)
                .orElseThrow(() -> new IllegalStateException("No workflow ref for instance " + instanceId));
        String processInstanceId = ref.getProcessInstanceId();
        if (!isRunning(processInstanceId)) {
            return;
        }
        runtimeService.deleteProcessInstance(processInstanceId, "withdrawn");
    }

    private boolean isRunning(String processInstanceId) {
        return runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count() > 0;
    }

    /** Describe the run after an engine call: tasks opened since {@code openBefore}, or the final outcome. */
    private WorkflowStep stepAfter(String processInstanceId, Set<String> openBefore) {
        if (!isRunning(processInstanceId)) {
            return new WorkflowStep(List.of(), outcomeOf(processInstanceId));
        }
        List<WorkflowTask> opened = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .orderByTaskCreateTime().asc().list().stream()
                .filter(t -> !openBefore.contains(t.getId()))
                .map(this::toWorkflowTask)
                .toList();
        return new WorkflowStep(opened, null);
    }

    private WorkflowOutcome outcomeOf(String processInstanceId) {
        List<HistoricActivityInstance> ends = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).activityType("endEvent").list();
        if (ends.size() != 1) {
            throw new IllegalStateException("Expected exactly one end event for process instance "
                    + processInstanceId + " but found " + ends.size());
        }
        return END_REJECTED.equals(ends.get(0).getActivityId()) ? WorkflowOutcome.REJECTED : WorkflowOutcome.APPROVED;
    }

    private WorkflowTask toWorkflowTask(Task task) {
        String assignee = task.getAssignee();
        String type = null;
        String id = null;
        if (assignee != null) {
            int split = assignee.indexOf(ASSIGNEE_SEPARATOR);
            if (split <= 0 || split == assignee.length() - 1) {
                throw new IllegalStateException("Malformed assignee '" + assignee + "' on task " + task.getId());
            }
            type = assignee.substring(0, split);
            id = assignee.substring(split + 1);
        }
        return new WorkflowTask(task.getId(), task.getTaskDefinitionKey(), task.getName(), type, id);
    }

    /**
     * Deploy the stored, published definition of one version for the tenant (once) and return the id of its
     * process definition. The XML comes from the immutable {@code process_definition} snapshot — never from
     * the classpath — so what runs is exactly what was published.
     */
    private String ensureDeployed(TenantId tenantId, String definitionKey, Integer version) {
        String deploymentKey = definitionKey + "_v" + version;
        Deployment deployment = repositoryService.createDeploymentQuery()
                .deploymentKey(deploymentKey).deploymentTenantId(tenantId.value())
                .orderByDeploymentTime().asc().list().stream().findFirst().orElse(null);
        if (deployment == null) {
            String bpmnXml = definitionRepository.findBpmn(tenantId, definitionKey, version)
                    .orElseThrow(() -> new IllegalStateException(
                            "No published definition " + definitionKey + " v" + version + " for tenant " + tenantId.value()));
            deployment = repositoryService.createDeployment()
                    .name(deploymentKey)
                    .key(deploymentKey)
                    .tenantId(tenantId.value())
                    .addString(deploymentKey + ".bpmn20.xml", bpmnXml)
                    .deploy();
        }
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        if (processDefinition == null) {
            throw new IllegalStateException("Deployment " + deploymentKey + " contains no process definition");
        }
        return processDefinition.getId();
    }
}
