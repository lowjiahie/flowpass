package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.OutboxPublisherPort;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.event.ApprovalInstanceWithdrawn;
import com.flowpass.approval.domain.event.DomainEvent;
import com.flowpass.approval.domain.runtime.ApprovalId;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.runtime.ApprovalStatus;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import com.flowpass.approval.domain.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Use-case tests for {@link WithdrawInstanceHandler}: initiator-only authorization, cancelling every open
 * task, and the terminal instance transition with outbox, engine termination and audit.
 */
@ExtendWith(MockitoExtension.class)
class WithdrawInstanceHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");
    private final ApproverRef initiator = new ApproverRef("USER", "u_1");
    private final ApproverRef assignee = new ApproverRef("USER", "u_2");

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ApprovalInstanceRepository instanceRepository;
    @Mock
    private OutboxPublisherPort outboxPublisher;
    @Mock
    private WorkflowEnginePort workflowEnginePort;
    @Mock
    private AuditRecordRepository auditRecordRepository;

    private ApprovalInstance instance() {
        return ApprovalInstance.hydrate(
                new ApprovalId("appr_biz-1"), tenant, "app_procurement", "biz-1", "purchase_request",
                initiator, null, 3, Map.of(), ApprovalStatus.RUNNING, 0L);
    }

    private ApprovalTask task() {
        return ApprovalTask.start(tenant, "appr_biz-1", "Approve purchase", assignee, "approveTask", "wf_task_1");
    }

    private WithdrawInstanceHandler handler() {
        return new WithdrawInstanceHandler(taskRepository, instanceRepository, outboxPublisher,
                workflowEnginePort, auditRecordRepository);
    }

    private WithdrawInstanceCommand command(ApproverRef actor) {
        return new WithdrawInstanceCommand(tenant, "appr_biz-1", actor, "changed my mind");
    }

    @Test
    void initiatorWithdrawsInstanceAndCancelsAllOpenTasks() {
        ApprovalInstance instance = instance();
        ApprovalTask first = task();
        ApprovalTask second = task();
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));
        when(taskRepository.findOpenByInstance(tenant, "appr_biz-1")).thenReturn(List.of(first, second));

        ApprovalInstanceDTO dto = handler().handle(command(initiator));

        assertThat(dto.status()).isEqualTo(ApprovalStatus.WITHDRAWN.name());
        assertThat(first.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(second.status()).isEqualTo(TaskStatus.CANCELLED);
        verify(taskRepository).updateStatus(first);
        verify(taskRepository).updateStatus(second);
        verify(instanceRepository).save(instance);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(outboxPublisher).append(eq(tenant), eq("appr_biz-1"), eq("ApprovalInstance"), events.capture());
        assertThat(events.getValue()).singleElement().isInstanceOf(ApprovalInstanceWithdrawn.class);
        verify(workflowEnginePort).terminate(tenant, "appr_biz-1");

        ArgumentCaptor<AuditRecord> audit = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository).append(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo(AuditAction.WITHDRAWN);
        assertThat(audit.getValue().actor()).isEqualTo(initiator);
        assertThat(audit.getValue().taskId()).isNull();
        assertThat(audit.getValue().comment()).isEqualTo("changed my mind");
    }

    @Test
    void forbidsAnyoneButTheInitiator() {
        ApprovalInstance instance = instance();
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));

        // Even the task assignee is not allowed to withdraw the instance.
        assertThatThrownBy(() -> handler().handle(command(assignee)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.FORBIDDEN));

        assertThat(instance.status()).isEqualTo(ApprovalStatus.RUNNING);
        verify(taskRepository, never()).updateStatus(any());
        verify(instanceRepository, never()).save(any());
        verify(workflowEnginePort, never()).terminate(any(), any());
        verify(auditRecordRepository, never()).append(any());
    }

    @Test
    void rejectsWithdrawOfAnInstanceThatIsAlreadyTerminal() {
        ApprovalInstance instance = instance();
        instance.approve(assignee);
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));

        assertThatThrownBy(() -> handler().handle(command(initiator)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.ILLEGAL_STATE));
        verify(workflowEnginePort, never()).terminate(any(), any());
    }

    @Test
    void rejectsUnknownInstance() {
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler().handle(command(initiator)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.INSTANCE_NOT_FOUND));
    }
}
