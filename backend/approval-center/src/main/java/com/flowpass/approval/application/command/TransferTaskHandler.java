package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.ApprovalTaskDtos;
import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowStep;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.contract.dto.ApprovalTaskDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.runtime.ApprovalId;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Transfer use case: the assignee (or a member of the assigned role) hands the task to somebody else.
 *
 * <p>The task stays open and keeps its place in the approval round — only who must act changes. The new person must
 * be eligible ({@link ParticipantGuard}); the engine task is re-assigned in the same transaction and the hand-over
 * is recorded on the audit ledger. A task handed over from a role becomes a task of one user.</p>
 */
@Service
public class TransferTaskHandler {

    private final TaskRepository taskRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final WorkflowEnginePort workflowEnginePort;
    private final AuditRecordRepository auditRecordRepository;
    private final TaskActionAuthorizer authorizer;
    private final ParticipantGuard participantGuard;

    public TransferTaskHandler(
            TaskRepository taskRepository,
            ApprovalInstanceRepository instanceRepository,
            WorkflowEnginePort workflowEnginePort,
            AuditRecordRepository auditRecordRepository,
            TaskActionAuthorizer authorizer,
            ParticipantGuard participantGuard) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.workflowEnginePort = workflowEnginePort;
        this.auditRecordRepository = auditRecordRepository;
        this.authorizer = authorizer;
        this.participantGuard = participantGuard;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_APPROVE + "')")
    @Transactional
    public ApprovalTaskDTO handle(TransferTaskCommand cmd) {
        ApprovalTask task = taskRepository.findById(cmd.tenantId(), cmd.taskId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TASK_NOT_FOUND,
                        "Task not found: " + cmd.taskId()));
        ApprovalInstance instance = instanceRepository.findById(cmd.tenantId(), new ApprovalId(task.instanceId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.INSTANCE_NOT_FOUND,
                        "Instance not found: " + task.instanceId()));
        authorizer.requireCanAct(task, instance, cmd.actor(), "transfer");
        if (task.workflowTaskRef() == null) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE,
                    "Task " + task.id() + " is not linked to a workflow step");
        }

        ApproverRef target = participantGuard.requireNewParticipant(instance, cmd.toUser());
        ApproverRef from = task.assignee();
        try {
            task.reassign(target);
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }
        taskRepository.updateAssignee(task);
        try {
            workflowEnginePort.reassign(cmd.tenantId(), task.instanceId(), task.workflowTaskRef(),
                    new CandidateApprover(target.type(), target.id()));
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }

        auditRecordRepository.append(AuditRecord.of(
                task.tenantId(), task.instanceId(), task.id(), cmd.actor(), AuditAction.TRANSFERRED,
                describe(from.type() + ":" + from.id() + " -> " + target.id(), cmd.comment()), Instant.now()));
        return ApprovalTaskDtos.of(task);
    }

    private static String describe(String what, String comment) {
        return comment == null || comment.isBlank() ? what : what + ": " + comment;
    }
}
