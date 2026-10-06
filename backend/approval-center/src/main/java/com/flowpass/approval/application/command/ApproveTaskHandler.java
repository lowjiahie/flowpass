package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.application.port.OutboxPublisherPort;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowOutcome;
import com.flowpass.approval.application.port.WorkflowStep;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.runtime.ApprovalId;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.runtime.ApprovalStatus;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Approve-a-task use case.
 *
 * <p>On the task side it enforces the lifecycle (only a {@code PENDING}/{@code CLAIMED} task can be
 * approved) with optimistic versioning. On the instance side, once no {@code PENDING} tasks remain the
 * running instance is advanced to {@code APPROVED}, emitting {@link
 * com.flowpass.approval.domain.event.ApprovalInstanceApproved}, and its drained events go into the
 * transactional outbox.</p>
 *
 * <p>M2 simplification: completion is decided by "no pending tasks left". Later milestones add the
 * approval-matrix round logic and business-rule confirmation.</p>
 */
@Service
public class ApproveTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(ApproveTaskHandler.class);

    private final TaskRepository taskRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final OutboxPublisherPort outboxPublisher;
    private final WorkflowEnginePort workflowEnginePort;
    private final AuditRecordRepository auditRecordRepository;
    private final TaskActionAuthorizer authorizer;
    private final WorkflowTaskMaterializer taskMaterializer;

    public ApproveTaskHandler(
            TaskRepository taskRepository,
            ApprovalInstanceRepository instanceRepository,
            OutboxPublisherPort outboxPublisher,
            WorkflowEnginePort workflowEnginePort,
            AuditRecordRepository auditRecordRepository,
            WorkflowTaskMaterializer taskMaterializer,
            TaskActionAuthorizer authorizer) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.outboxPublisher = outboxPublisher;
        this.workflowEnginePort = workflowEnginePort;
        this.auditRecordRepository = auditRecordRepository;
        this.taskMaterializer = taskMaterializer;
        this.authorizer = authorizer;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_APPROVE + "')")
    @Transactional
    public ApprovalInstanceDTO handle(ApproveTaskCommand cmd) {
        ApprovalTask task = taskRepository.findById(cmd.tenantId(), cmd.taskId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TASK_NOT_FOUND,
                        "Task not found: " + cmd.taskId()));

        // 0. Resource-level rule: only the assignee (or a holder of the assigned role) may act, and never
        //    the initiator through a role.
        ApprovalInstance instance = instanceRepository.findById(cmd.tenantId(), new ApprovalId(task.instanceId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.INSTANCE_NOT_FOUND,
                        "Instance not found: " + task.instanceId()));
        authorizer.requireCanAct(task, instance, cmd.actor(), "approve");

        if (task.workflowTaskRef() == null) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE,
                    "Task " + task.id() + " is not linked to a workflow step");
        }

        // 1. Task transition (aggregate owns its lifecycle; version check in the adapter).
        try {
            task.approve();
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }
        taskRepository.updateStatus(task);

        // 1a. Record the action on the immutable audit ledger (task-scoped). Append-only.
        auditRecordRepository.append(AuditRecord.of(
                task.tenantId(),
                task.instanceId(),
                task.id(),
                cmd.actor(),
                AuditAction.APPROVED,
                cmd.comment(),
                Instant.now()));

        // 3. Advance the engine (the source of truth for routing) with this approval. It tells us which
        //    tasks open next and whether, and how, the run ended; nothing is inferred from task counts.
        WorkflowStep step = workflowEnginePort.completeTask(
                cmd.tenantId(), task.instanceId(), task.workflowTaskRef(), WorkflowOutcome.APPROVED);
        taskMaterializer.materialize(cmd.tenantId(), task.instanceId(), step.newTasks());

        // 4. The run reached an end node: move the running instance to the outcome the engine reports.
        if (step.finished() && instance.status() == ApprovalStatus.RUNNING) {
            if (step.outcome() == WorkflowOutcome.APPROVED) {
                instance.approve(cmd.actor());
            } else {
                instance.reject(cmd.actor());
            }
            instanceRepository.save(instance);
            outboxPublisher.append(instance.tenantId(), instance.id().value(), "ApprovalInstance",
                    instance.drainEvents());
        }

        return toDto(instance);
    }

    private ApprovalInstanceDTO toDto(ApprovalInstance instance) {
        return new ApprovalInstanceDTO(
                instance.id().value(),
                instance.businessKey(),
                instance.templateKey(),
                instance.status().name(),
                instance.initiator().type(),
                instance.initiator().id(),
                instance.money() == null ? null : instance.money().amount(),
                instance.money() == null ? null : instance.money().currency(),
                instance.definitionVersion(),
                Instant.now().toString());
    }
}
