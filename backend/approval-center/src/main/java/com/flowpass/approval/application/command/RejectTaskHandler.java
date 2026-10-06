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
 * Reject-a-task use case.
 *
 * <p>On the task side it enforces the lifecycle (only a {@code PENDING}/{@code CLAIMED} task can be
 * rejected) with optimistic versioning. On the instance side, a rejection is terminal: the running
 * instance is moved straight to {@code REJECTED} — regardless of any still-pending sibling tasks —
 * emitting {@link com.flowpass.approval.domain.event.ApprovalInstanceRejected}, and its drained events
 * go into the transactional outbox. The process engine is advanced so the workflow terminates.</p>
 */
@Service
public class RejectTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(RejectTaskHandler.class);

    private final TaskRepository taskRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final OutboxPublisherPort outboxPublisher;
    private final WorkflowEnginePort workflowEnginePort;
    private final AuditRecordRepository auditRecordRepository;
    private final TaskActionAuthorizer authorizer;

    public RejectTaskHandler(
            TaskRepository taskRepository,
            ApprovalInstanceRepository instanceRepository,
            OutboxPublisherPort outboxPublisher,
            WorkflowEnginePort workflowEnginePort,
            AuditRecordRepository auditRecordRepository,
            TaskActionAuthorizer authorizer) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.outboxPublisher = outboxPublisher;
        this.workflowEnginePort = workflowEnginePort;
        this.auditRecordRepository = auditRecordRepository;
        this.authorizer = authorizer;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_REJECT + "')")
    @Transactional
    public ApprovalInstanceDTO handle(RejectTaskCommand cmd) {
        ApprovalTask task = taskRepository.findById(cmd.tenantId(), cmd.taskId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TASK_NOT_FOUND,
                        "Task not found: " + cmd.taskId()));

        // 0. Resource-level rule: only the assignee (or a holder of the assigned role) may act, and never
        //    the initiator through a role.
        ApprovalInstance instance = instanceRepository.findById(cmd.tenantId(), new ApprovalId(task.instanceId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.INSTANCE_NOT_FOUND,
                        "Instance not found: " + task.instanceId()));
        authorizer.requireCanAct(task, instance, cmd.actor(), "reject");

        if (task.workflowTaskRef() == null) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE,
                    "Task " + task.id() + " is not linked to a workflow step");
        }

        // 1. Task transition (aggregate owns its lifecycle; version check in the adapter).
        try {
            task.reject();
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
                AuditAction.REJECTED,
                cmd.comment(),
                Instant.now()));

        // 3. Tell the engine about the rejection. The model routes it to its rejected end event and removes
        //    the sibling tasks of the step; a model that does not end on a rejection is stopped explicitly,
        //    because a rejection is always terminal.
        WorkflowStep step = workflowEnginePort.completeTask(
                cmd.tenantId(), task.instanceId(), task.workflowTaskRef(), WorkflowOutcome.REJECTED);

        if (instance.status() == ApprovalStatus.RUNNING) {
            instance.reject(cmd.actor());
            instanceRepository.save(instance);
            outboxPublisher.append(instance.tenantId(), instance.id().value(), "ApprovalInstance",
                    instance.drainEvents());
            if (!step.finished()) {
                workflowEnginePort.terminate(instance.tenantId(), instance.id().value());
            }
            // The business view must follow the engine: close the siblings it removed.
            for (ApprovalTask open : taskRepository.findOpenByInstance(cmd.tenantId(), task.instanceId())) {
                open.withdraw();
                taskRepository.updateStatus(open);
            }
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
