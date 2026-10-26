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
 * Add-sign use case: an approver asks another user to approve as well. The approval round the task belongs to then
 * needs the added user's approval too before it can complete (and, like anyone in the round, they may reject).
 *
 * <p>The engine opens a task for the new approver inside the same parallel step; it is mirrored as a business
 * task like every engine task. Only the task's own round is affected — later rounds are untouched — and the user
 * must be eligible ({@link ParticipantGuard}).</p>
 */
@Service
public class AddApproverHandler {

    /** Upper bound on the open tasks of one request, so add-sign cannot be used to flood it. */
    static final int MAX_OPEN_TASKS = 50;

    private final TaskRepository taskRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final WorkflowEnginePort workflowEnginePort;
    private final AuditRecordRepository auditRecordRepository;
    private final TaskActionAuthorizer authorizer;
    private final ParticipantGuard participantGuard;
    private final WorkflowTaskMaterializer taskMaterializer;

    public AddApproverHandler(
            TaskRepository taskRepository,
            ApprovalInstanceRepository instanceRepository,
            WorkflowEnginePort workflowEnginePort,
            AuditRecordRepository auditRecordRepository,
            TaskActionAuthorizer authorizer,
            ParticipantGuard participantGuard,
            WorkflowTaskMaterializer taskMaterializer) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.workflowEnginePort = workflowEnginePort;
        this.auditRecordRepository = auditRecordRepository;
        this.authorizer = authorizer;
        this.participantGuard = participantGuard;
        this.taskMaterializer = taskMaterializer;
    }

    /** @return the task opened for the added approver */
    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_APPROVE + "')")
    @Transactional
    public ApprovalTaskDTO handle(AddApproverCommand cmd) {
        ApprovalTask task = taskRepository.findById(cmd.tenantId(), cmd.taskId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TASK_NOT_FOUND,
                        "Task not found: " + cmd.taskId()));
        ApprovalInstance instance = instanceRepository.findById(cmd.tenantId(), new ApprovalId(task.instanceId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.INSTANCE_NOT_FOUND,
                        "Instance not found: " + task.instanceId()));
        authorizer.requireCanAct(task, instance, cmd.actor(), "add an approver to");
        if (task.workflowTaskRef() == null) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE,
                    "Task " + task.id() + " is not linked to a workflow step");
        }
        if (task.status() != com.flowpass.approval.domain.task.TaskStatus.PENDING
                && task.status() != com.flowpass.approval.domain.task.TaskStatus.CLAIMED) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, "Cannot add an approver to a task in " + task.status());
        }

        ApproverRef target = participantGuard.requireNewParticipant(instance, cmd.userId());
        if (taskRepository.findOpenByInstance(cmd.tenantId(), task.instanceId()).size() >= MAX_OPEN_TASKS) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED,
                    "A request can have at most " + MAX_OPEN_TASKS + " open tasks");
        }

        WorkflowStep step;
        try {
            step = workflowEnginePort.addApprover(cmd.tenantId(), task.instanceId(), task.workflowTaskRef(),
                    new CandidateApprover(target.type(), target.id()));
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }
        List<ApprovalTask> created = taskMaterializer.materialize(cmd.tenantId(), task.instanceId(), step.newTasks());
        if (created.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, "The workflow did not open a task for the new approver");
        }

        auditRecordRepository.append(AuditRecord.of(
                task.tenantId(), task.instanceId(), task.id(), cmd.actor(), AuditAction.APPROVER_ADDED,
                cmd.comment() == null || cmd.comment().isBlank()
                        ? "added " + target.id() : "added " + target.id() + ": " + cmd.comment(),
                Instant.now()));
        return ApprovalTaskDtos.of(created.get(0));
    }
}
