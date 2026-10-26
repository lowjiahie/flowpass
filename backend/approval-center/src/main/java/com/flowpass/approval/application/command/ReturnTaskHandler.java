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
 * Return use case: an approver of a later round sends the request back to the previous round instead of deciding.
 *
 * <p>The approver's task is closed as {@code RETURNED}; the engine ends the current round without a decision
 * (discarding the approvals already given in it) and opens the previous round again for its approvers, who must
 * approve once more. The other open tasks of the discarded round are cancelled, and the tasks the engine reopens are
 * mirrored as new business tasks — the earlier ones stay in the history. The instance remains {@code RUNNING}.
 * A definition that cannot route a return, or a request still in its first round, is refused and nothing changes.</p>
 */
@Service
public class ReturnTaskHandler {

    private final TaskRepository taskRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final WorkflowEnginePort workflowEnginePort;
    private final AuditRecordRepository auditRecordRepository;
    private final TaskActionAuthorizer authorizer;
    private final WorkflowTaskMaterializer taskMaterializer;

    public ReturnTaskHandler(
            TaskRepository taskRepository,
            ApprovalInstanceRepository instanceRepository,
            WorkflowEnginePort workflowEnginePort,
            AuditRecordRepository auditRecordRepository,
            TaskActionAuthorizer authorizer,
            WorkflowTaskMaterializer taskMaterializer) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.workflowEnginePort = workflowEnginePort;
        this.auditRecordRepository = auditRecordRepository;
        this.authorizer = authorizer;
        this.taskMaterializer = taskMaterializer;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_APPROVE + "')")
    @Transactional
    public ApprovalInstanceDTO handle(ReturnTaskCommand cmd) {
        ApprovalTask task = taskRepository.findById(cmd.tenantId(), cmd.taskId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TASK_NOT_FOUND,
                        "Task not found: " + cmd.taskId()));
        ApprovalInstance instance = instanceRepository.findById(cmd.tenantId(), new ApprovalId(task.instanceId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.INSTANCE_NOT_FOUND,
                        "Instance not found: " + task.instanceId()));
        authorizer.requireCanAct(task, instance, cmd.actor(), "return");
        if (task.workflowTaskRef() == null) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE,
                    "Task " + task.id() + " is not linked to a workflow step");
        }

        // The rest of the round is about to be discarded by the engine; remember it before anything changes.
        List<ApprovalTask> rest = taskRepository.findOpenByInstance(cmd.tenantId(), task.instanceId()).stream()
                .filter(open -> !open.id().equals(task.id()))
                .toList();

        try {
            task.returnBack();
        } catch (IllegalStateException e) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }
        taskRepository.updateStatus(task);

        WorkflowStep step;
        try {
            step = workflowEnginePort.returnToPreviousRound(cmd.tenantId(), task.instanceId(), task.workflowTaskRef());
        } catch (IllegalStateException e) {
            // Nothing was changed in the engine; the transaction rolls back the task update above too.
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE, e.getMessage());
        }

        // The business view follows the engine: the discarded round's other tasks are closed, the reopened round's
        // tasks are created (in that order, so the new ones are not caught by the cleanup).
        for (ApprovalTask other : rest) {
            other.withdraw();
            taskRepository.updateStatus(other);
        }
        taskMaterializer.materialize(cmd.tenantId(), task.instanceId(), step.newTasks());

        auditRecordRepository.append(AuditRecord.of(
                task.tenantId(), task.instanceId(), task.id(), cmd.actor(), AuditAction.RETURNED,
                cmd.comment(), Instant.now()));

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
