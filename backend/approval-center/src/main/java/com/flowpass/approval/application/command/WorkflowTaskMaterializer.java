package com.flowpass.approval.application.command;

import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowTask;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.delegation.Delegation;
import com.flowpass.approval.domain.delegation.DelegationRepository;
import com.flowpass.approval.domain.runtime.ApprovalId;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.sla.SlaPolicy;
import com.flowpass.approval.domain.sla.SlaPolicyRepository;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the user tasks the process engine opened into business {@link ApprovalTask}s.
 *
 * <p>The engine decides <em>which</em> tasks exist; this is the single place where each one becomes a
 * platform task (own id, assignee, node key, opaque engine reference). Used by every use case that can
 * advance a run — start, approve, return, add-sign — so the two views cannot drift. An engine task without an
 * assignee (e.g. the manual-review parking task) has no accountable approver, so no business task is created.</p>
 *
 * <p>Two things are decided <em>at the moment a task opens</em>, because both depend on when that is:</p>
 * <ul>
 *   <li><b>Delegation.</b> If the assignee has an active {@link Delegation}, the task goes to the delegate
 *       instead (the engine task is re-assigned and the hand-over audited). One level only; skipped when the
 *       delegate is the initiator or already involved in the request.</li>
 *   <li><b>Deadlines.</b> If the template has an {@link SlaPolicy}, the task is given its due and reminder times.</li>
 * </ul>
 */
@Component
public class WorkflowTaskMaterializer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskMaterializer.class);
    private static final ApproverRef DELEGATION_ACTOR = new ApproverRef("SYSTEM", "delegation");

    private final TaskRepository taskRepository;
    private final DelegationRepository delegations;
    private final ApprovalInstanceRepository instances;
    private final SlaPolicyRepository slaPolicies;
    private final WorkflowEnginePort engine;
    private final AuditRecordRepository audit;
    private final Clock clock;

    /** A plain mirror: no delegation, no deadlines (for callers and tests that do not need them). */
    public WorkflowTaskMaterializer(TaskRepository taskRepository) {
        this(taskRepository, null, null, null, null, null, Clock.systemUTC());
    }

    @Autowired
    public WorkflowTaskMaterializer(
            TaskRepository taskRepository,
            DelegationRepository delegations,
            ApprovalInstanceRepository instances,
            SlaPolicyRepository slaPolicies,
            WorkflowEnginePort engine,
            AuditRecordRepository audit,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.delegations = delegations;
        this.instances = instances;
        this.slaPolicies = slaPolicies;
        this.engine = engine;
        this.audit = audit;
        this.clock = clock;
    }

    /** Persist one {@code PENDING} task per assigned engine task; returns the tasks created. */
    public List<ApprovalTask> materialize(TenantId tenantId, String instanceId, List<WorkflowTask> workflowTasks) {
        List<WorkflowTask> assigned = new ArrayList<>();
        for (WorkflowTask workflowTask : workflowTasks) {
            if (workflowTask.assigned()) {
                assigned.add(workflowTask);
            } else {
                log.warn("Instance {} (tenant {}): engine task at node {} has no assignee — left for manual handling",
                        instanceId, tenantId.value(), workflowTask.nodeKey());
            }
        }
        if (assigned.isEmpty()) {
            return List.of();
        }

        Instant now = clock.instant();
        ApprovalInstance instance = instances == null ? null
                : instances.findById(tenantId, new ApprovalId(instanceId)).orElse(null);
        SlaPolicy policy = slaPolicies == null || instance == null ? null
                : slaPolicies.find(tenantId, instance.templateKey()).orElse(null);
        Set<String> usersInBatch = new HashSet<>();
        assigned.stream().filter(t -> "USER".equals(t.assigneeType())).forEach(t -> usersInBatch.add(t.assigneeId()));

        List<ApprovalTask> created = new ArrayList<>();
        for (WorkflowTask workflowTask : assigned) {
            ApproverRef assignee = new ApproverRef(workflowTask.assigneeType(), workflowTask.assigneeId());
            Optional<Delegation> delegation = delegationFor(tenantId, instance, instanceId, assignee, usersInBatch, now);
            ApproverRef actual = delegation.map(d -> new ApproverRef("USER", d.delegateId())).orElse(assignee);
            delegation.ifPresent(d -> usersInBatch.add(d.delegateId()));

            ApprovalTask task = ApprovalTask.start(tenantId, instanceId, workflowTask.name(), actual,
                    workflowTask.nodeKey(), workflowTask.ref());
            if (policy != null) {
                task.scheduleDeadlines(policy.dueAt(now), policy.remindAt(now));
            }
            taskRepository.save(task);
            if (delegation.isPresent()) {
                engine.reassign(tenantId, instanceId, workflowTask.ref(), new CandidateApprover("USER", actual.id()));
                audit.append(AuditRecord.of(tenantId, instanceId, task.id(), DELEGATION_ACTOR, AuditAction.DELEGATED,
                        "USER:" + assignee.id() + " -> " + actual.id() + " (delegation " + delegation.get().id() + ")", now));
            }
            created.add(task);
        }
        return created;
    }

    /** The delegation that applies to this assignee right now and whose delegate may take the task, if any. */
    private Optional<Delegation> delegationFor(TenantId tenantId, ApprovalInstance instance, String instanceId,
                                               ApproverRef assignee, Set<String> usersInBatch, Instant now) {
        if (delegations == null || instance == null || engine == null || audit == null
                || !"USER".equals(assignee.type())) {
            return Optional.empty();
        }
        return delegations.findActive(tenantId, assignee.id(), now).filter(d ->
                !instance.isInitiatedBy(new ApproverRef("USER", d.delegateId()))
                        && !usersInBatch.contains(d.delegateId())
                        && !taskRepository.existsUserTask(tenantId, instanceId, d.delegateId()));
    }
}
