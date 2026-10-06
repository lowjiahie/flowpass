package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.OutboxPublisherPort;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowOutcome;
import com.flowpass.approval.application.port.WorkflowStep;
import com.flowpass.approval.application.port.WorkflowTask;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;
import com.flowpass.approval.domain.event.ApprovalInstanceRejected;
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
 * Use-case tests for {@link RejectTaskHandler} — task rejection, terminal instance state, and the
 * failure paths (task not found). Ports are mocked.
 */
@ExtendWith(MockitoExtension.class)
class RejectTaskHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");
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
    @Mock
    private OrgDirectory orgDirectory;

    private ApprovalInstance instance() {
        return ApprovalInstance.hydrate(
                new ApprovalId("appr_biz-1"), tenant, "app_procurement", "biz-1", "purchase_request",
                new ApproverRef("USER", "u_1"), null, 3, Map.of(), ApprovalStatus.RUNNING, 0L);
    }

    private ApprovalTask task() {
        return ApprovalTask.start(tenant, "appr_biz-1", "Approve purchase", assignee, "approveTask", "wf_task_1");
    }

    private RejectTaskHandler handler() {
        return new RejectTaskHandler(taskRepository, instanceRepository, outboxPublisher, workflowEnginePort,
                auditRecordRepository, new TaskActionAuthorizer(orgDirectory));
    }

    private void engineReturns(WorkflowStep step) {
        when(workflowEnginePort.completeTask(eq(tenant), eq("appr_biz-1"), eq("wf_task_1"),
                eq(WorkflowOutcome.REJECTED))).thenReturn(step);
    }

    private RejectTaskCommand command(ApprovalTask task) {
        return new RejectTaskCommand(tenant, task.id(), assignee, "looks good");
    }

    @Test
    void rejectsInstanceAndPublishesEvent() {
        ApprovalTask task = task();
        ApprovalInstance instance = instance();
        instance.drainEvents();
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));
        engineReturns(new WorkflowStep(List.of(), WorkflowOutcome.REJECTED));

        ApprovalInstanceDTO dto = handler().handle(command(task));

        assertThat(dto.status()).isEqualTo(ApprovalStatus.REJECTED.name());
        verify(taskRepository).updateStatus(task);
        assertThat(task.status()).isEqualTo(TaskStatus.REJECTED);

        ArgumentCaptor<ApprovalInstance> instanceCaptor = ArgumentCaptor.forClass(ApprovalInstance.class);
        verify(instanceRepository).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(instanceCaptor.getValue().version()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> eventCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxPublisher).append(eq(tenant), eq("appr_biz-1"), eq("ApprovalInstance"), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).hasSize(1);
        assertThat(eventCaptor.getValue().get(0)).isInstanceOf(ApprovalInstanceRejected.class);
        verify(workflowEnginePort).completeTask(tenant, "appr_biz-1", "wf_task_1", WorkflowOutcome.REJECTED);
        verify(workflowEnginePort, never()).terminate(any(), any());

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.REJECTED);
        assertThat(auditCaptor.getValue().instanceId()).isEqualTo("appr_biz-1");
        assertThat(auditCaptor.getValue().taskId()).isEqualTo(task.id());
        assertThat(auditCaptor.getValue().actor()).isEqualTo(assignee);
    }

    @Test
    void rejectsUnknownTask() {
        when(taskRepository.findById(eq(tenant), eq("task_missing"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler().handle(new RejectTaskCommand(tenant, "task_missing", assignee, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.TASK_NOT_FOUND));
        verify(taskRepository, never()).updateStatus(any());
    }

    @Test
    void forbidsActorThatIsNotTheAssignee() {
        ApprovalTask task = task();
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));

        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance()));
        ApproverRef stranger = new ApproverRef("USER", "u_9");
        assertThatThrownBy(() -> handler().handle(new RejectTaskCommand(tenant, task.id(), stranger, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.FORBIDDEN));

        // Nothing changed: the task is still open and no side effect ran.
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        verify(taskRepository, never()).updateStatus(any());
        verify(auditRecordRepository, never()).append(any());
        verify(outboxPublisher, never()).append(any(), any(), any(), any());
        verify(workflowEnginePort, never()).completeTask(any(), any(), any(), any());
    }

    @Test
    void recordsCommentOnAuditLedger() {
        ApprovalTask task = task();
        ApprovalInstance instance = instance();
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));

        engineReturns(new WorkflowStep(List.of(), WorkflowOutcome.REJECTED));

        handler().handle(new RejectTaskCommand(tenant, task.id(), assignee, "because"));

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository).append(captor.capture());
        assertThat(captor.getValue().comment()).isEqualTo("because");
    }

    @Test
    void closesTheSiblingTasksTheEngineRemoved() {
        ApprovalTask task = task();
        ApprovalTask sibling = ApprovalTask.start(tenant, "appr_biz-1", "Approve purchase",
                new ApproverRef("USER", "u_3"), "approveTask", "wf_task_2");
        ApprovalInstance instance = instance();
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));
        when(taskRepository.findOpenByInstance(tenant, "appr_biz-1")).thenReturn(List.of(sibling));
        engineReturns(new WorkflowStep(List.of(), WorkflowOutcome.REJECTED));

        handler().handle(command(task));

        assertThat(sibling.status()).isEqualTo(TaskStatus.CANCELLED);
        verify(taskRepository).updateStatus(sibling);
    }

    @Test
    void stopsTheEngineWhenTheModelDidNotEndOnARejection() {
        // A rejection is always terminal, even if a (mis)configured model would keep running.
        ApprovalTask task = task();
        ApprovalInstance instance = instance();
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));
        engineReturns(new WorkflowStep(List.of(), null));

        ApprovalInstanceDTO dto = handler().handle(command(task));

        assertThat(dto.status()).isEqualTo(ApprovalStatus.REJECTED.name());
        verify(workflowEnginePort).terminate(tenant, "appr_biz-1");
    }

    @Test
    void refusesATaskThatIsNotLinkedToAWorkflowStep() {
        ApprovalTask unlinked = ApprovalTask.hydrate(
                "task_old", tenant, "appr_biz-1", "Approve purchase", "USER", "u_2",
                TaskStatus.PENDING, 0L, null, null);
        when(taskRepository.findById(eq(tenant), eq("task_old"))).thenReturn(Optional.of(unlinked));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance()));

        assertThatThrownBy(() -> handler().handle(new RejectTaskCommand(tenant, "task_old", assignee, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.ILLEGAL_STATE));
        verify(workflowEnginePort, never()).completeTask(any(), any(), any(), any());
    }
}
