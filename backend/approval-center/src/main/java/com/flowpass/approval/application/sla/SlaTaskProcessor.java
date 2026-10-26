package com.flowpass.approval.application.sla;

import com.flowpass.approval.application.command.ParticipantGuard;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.OutboxPublisherPort;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;
import com.flowpass.approval.domain.event.ApprovalTaskEscalated;
import com.flowpass.approval.domain.event.ApprovalTaskOverdue;
import com.flowpass.approval.domain.event.ApprovalTaskReminded;
import com.flowpass.approval.domain.event.DomainEvent;
import com.flowpass.approval.domain.runtime.ApprovalId;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.runtime.ApprovalStatus;
import com.flowpass.approval.domain.sla.SlaPolicy;
import com.flowpass.approval.domain.sla.SlaPolicyRepository;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Processes one task's deadline event in its own transaction, always scoped to the task's own tenant.
 *
 * <p>Each step first <em>claims</em> the event with a conditional update (reminded / overdue handled), so with
 * several nodes or a repeated tick it happens once. If anything after the claim fails the whole transaction —
 * claim included — rolls back and the next tick tries again; nothing is ever half-done.</p>
 */
@Component
public class SlaTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(SlaTaskProcessor.class);
    private static final ApproverRef SLA_ACTOR = new ApproverRef("SYSTEM", "sla");
    private static final String AGGREGATE = "ApprovalTask";

    private final TaskRepository taskRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final SlaPolicyRepository slaPolicies;
    private final OrgDirectory orgDirectory;
    private final ParticipantGuard participantGuard;
    private final WorkflowEnginePort engine;
    private final AuditRecordRepository audit;
    private final OutboxPublisherPort outbox;

    public SlaTaskProcessor(
            TaskRepository taskRepository,
            ApprovalInstanceRepository instanceRepository,
            SlaPolicyRepository slaPolicies,
            OrgDirectory orgDirectory,
            ParticipantGuard participantGuard,
            WorkflowEnginePort engine,
            AuditRecordRepository audit,
            OutboxPublisherPort outbox) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.slaPolicies = slaPolicies;
        this.orgDirectory = orgDirectory;
        this.participantGuard = participantGuard;
        this.engine = engine;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** Remind the assignee that the task is approaching its deadline. @return true when a reminder was sent */
    @Transactional
    public boolean remind(TenantId tenantId, String taskId, Instant now) {
        if (!taskRepository.claimReminder(tenantId, taskId, now)) {
            return false;
        }
        ApprovalTask task = taskRepository.findById(tenantId, taskId).orElse(null);
        // Too late for a reminder if the deadline already passed — the overdue handling covers it.
        if (task == null || task.isOverdueAt(now)) {
            return false;
        }
        outbox.append(tenantId, taskId, AGGREGATE, List.of(new ApprovalTaskReminded(taskId, tenantId.value(),
                task.instanceId(), task.assignee().type(), task.assignee().id(), task.dueAt(), now)));
        return true;
    }

    /** What happened to an overdue task. */
    public enum OverdueOutcome { NONE, NOTIFIED, ESCALATED }

    /** The task passed its deadline: tell the world, and apply the template's overdue action. */
    @Transactional
    public OverdueOutcome handleOverdue(TenantId tenantId, String taskId, Instant now) {
        if (!taskRepository.claimOverdue(tenantId, taskId, now)) {
            return OverdueOutcome.NONE;
        }
        ApprovalTask task = taskRepository.findById(tenantId, taskId).orElse(null);
        ApprovalInstance instance = task == null ? null
                : instanceRepository.findById(tenantId, new ApprovalId(task.instanceId())).orElse(null);
        if (instance == null || instance.status() != ApprovalStatus.RUNNING) {
            return OverdueOutcome.NONE;
        }

        ApproverRef assignee = task.assignee();
        List<DomainEvent> events = new ArrayList<>();
        events.add(new ApprovalTaskOverdue(taskId, tenantId.value(), task.instanceId(), assignee.type(),
                assignee.id(), task.dueAt(), now));

        SlaPolicy policy = slaPolicies.find(tenantId, instance.templateKey()).orElse(null);
        String problem = null;
        String target = null;
        if (policy == null) {
            problem = "the template no longer has an SLA policy";
        } else {
            switch (policy.onOverdue()) {
                case NOTIFY_ONLY -> { }
                case ESCALATE_TO_MANAGER -> {
                    Optional<String> manager = "USER".equals(assignee.type())
                            ? orgDirectory.managerOf(tenantId, assignee.id()) : Optional.empty();
                    target = manager.orElse(policy.escalateToUser());
                    if (target == null) {
                        problem = "no manager is recorded for " + assignee.id() + " and the policy names no fallback user";
                    }
                }
                case ESCALATE_TO_USER -> target = policy.escalateToUser();
            }
        }

        ApproverRef escalatedTo = null;
        if (target != null) {
            try {
                escalatedTo = participantGuard.requireNewParticipant(instance, target);
            } catch (BusinessException e) {
                problem = target + " cannot take the task: " + e.getMessage();
            }
        }

        if (escalatedTo != null) {
            task.reassign(escalatedTo);
            taskRepository.updateAssignee(task);
            engine.reassign(tenantId, task.instanceId(), task.workflowTaskRef(),
                    new CandidateApprover(escalatedTo.type(), escalatedTo.id()));
            events.add(new ApprovalTaskEscalated(taskId, tenantId.value(), task.instanceId(), assignee.type(),
                    assignee.id(), escalatedTo.id(), now));
            audit.append(AuditRecord.of(tenantId, task.instanceId(), taskId, SLA_ACTOR, AuditAction.ESCALATED,
                    assignee.type() + ":" + assignee.id() + " -> " + escalatedTo.id(), now));
        }
        audit.append(AuditRecord.of(tenantId, task.instanceId(), taskId, SLA_ACTOR, AuditAction.OVERDUE,
                escalatedTo != null ? "overdue; escalated to " + escalatedTo.id()
                        : problem != null ? "overdue; not escalated: " + problem : "overdue", now));
        outbox.append(tenantId, taskId, AGGREGATE, events);
        if (problem != null) {
            log.warn("Task {} (tenant {}) is overdue and was not escalated: {}", taskId, tenantId.value(), problem);
        }
        return escalatedTo != null ? OverdueOutcome.ESCALATED : OverdueOutcome.NOTIFIED;
    }
}
