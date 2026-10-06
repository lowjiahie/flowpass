package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.application.port.OutboxPublisherPort;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
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

/**
 * Withdraw-an-instance use case — an instance-level operation owned by the initiator.
 *
 * <p>Only the principal that started the instance may withdraw it (domain rule
 * {@link ApprovalInstance#isInitiatedBy}). Withdrawal is terminal: every still-open task is cancelled,
 * the instance moves to {@code WITHDRAWN} emitting
 * {@link com.flowpass.approval.domain.event.ApprovalInstanceWithdrawn} into the transactional outbox,
 * the process engine is terminated and one audit entry is appended — all in one local transaction.</p>
 */
@Service
public class WithdrawInstanceHandler {

    private static final Logger log = LoggerFactory.getLogger(WithdrawInstanceHandler.class);

    private final TaskRepository taskRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final OutboxPublisherPort outboxPublisher;
    private final WorkflowEnginePort workflowEnginePort;
    private final AuditRecordRepository auditRecordRepository;

    public WithdrawInstanceHandler(
            TaskRepository taskRepository,
            ApprovalInstanceRepository instanceRepository,
            OutboxPublisherPort outboxPublisher,
            WorkflowEnginePort workflowEnginePort,
            AuditRecordRepository auditRecordRepository) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.outboxPublisher = outboxPublisher;
        this.workflowEnginePort = workflowEnginePort;
        this.auditRecordRepository = auditRecordRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_CREATE + "')")
    @Transactional
    public ApprovalInstanceDTO handle(WithdrawInstanceCommand cmd) {
        ApprovalInstance instance = instanceRepository.findById(cmd.tenantId(), new ApprovalId(cmd.instanceId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.INSTANCE_NOT_FOUND,
                        "Instance not found: " + cmd.instanceId()));

        // 1. Resource-level rule (domain): only the initiator may withdraw.
        if (!instance.isInitiatedBy(cmd.actor())) {
            log.warn("Denied withdraw on instance {} (tenant {}) for actor {}:{} — not the initiator",
                    cmd.instanceId(), cmd.tenantId().value(), cmd.actor().type(), cmd.actor().id());
            throw new BusinessException(BusinessErrorCode.FORBIDDEN,
                    "Only the initiator can withdraw instance " + cmd.instanceId());
        }
        if (instance.status() != ApprovalStatus.RUNNING) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE,
                    "Cannot withdraw an instance in " + instance.status());
        }

        // 2. Cancel every open task (the aggregate owns each task's transition; version-checked on save).
        for (ApprovalTask task : taskRepository.findOpenByInstance(cmd.tenantId(), instance.id().value())) {
            task.withdraw();
            taskRepository.updateStatus(task);
        }

        // 3. Terminal instance transition + outbox, then stop the engine (no end-node walk).
        instance.withdraw(cmd.actor());
        instanceRepository.save(instance);
        outboxPublisher.append(instance.tenantId(), instance.id().value(), "ApprovalInstance",
                instance.drainEvents());
        workflowEnginePort.terminate(instance.tenantId(), instance.id().value());

        // 4. Immutable audit entry (instance-scoped).
        auditRecordRepository.append(AuditRecord.of(
                instance.tenantId(), instance.id().value(), null, cmd.actor(),
                AuditAction.WITHDRAWN, cmd.comment(), Instant.now()));

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
