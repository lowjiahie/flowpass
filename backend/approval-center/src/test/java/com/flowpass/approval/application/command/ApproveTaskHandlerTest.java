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
import com.flowpass.approval.domain.event.ApprovalInstanceApproved;
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
 * Use-case tests for {@link ApproveTaskHandler} — task transition, instance completion, and the two
 * failure paths (task not found, non-terminal task in progress). Ports are mocked.
 */
@ExtendWith(MockitoExtension.class)
class ApproveTaskHandlerTest {

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

    private ApproveTaskHandler handler() {
        return new ApproveTaskHandler(taskRepository, instanceRepository, outboxPublisher, workflowEnginePort,
                auditRecordRepository, new WorkflowTaskMaterializer(taskRepository),
                new TaskActionAuthorizer(orgDirectory));
    }

    private void engineReturns(WorkflowStep step) {
        when(workflowEnginePort.completeTask(eq(tenant), eq("appr_biz-1"), eq("wf_task_1"),
                eq(WorkflowOutcome.APPROVED))).thenReturn(step);
    }

    private ApproveTaskCommand command(ApprovalTask task) {
        return new ApproveTaskCommand(tenant, task.id(), assignee, "looks good");
    }

    @Test
    void advancesInstanceWhenLastPendingTaskApproved() {
        ApprovalTask task = task();
        ApprovalInstance instance = instance();
        // Simulate a persisted instance: the created event was already drained at start time.
        instance.drainEvents();
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));
        engineReturns(new WorkflowStep(List.of(), WorkflowOutcome.APPROVED));

        ApprovalInstanceDTO dto = handler().handle(command(task));

        assertThat(dto.status()).isEqualTo(ApprovalStatus.APPROVED.name());
        verify(taskRepository).updateStatus(task);
        assertThat(task.status()).isEqualTo(TaskStatus.APPROVED);

        ArgumentCaptor<ApprovalInstance> instanceCaptor = ArgumentCaptor.forClass(ApprovalInstance.class);
        verify(instanceRepository).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(instanceCaptor.getValue().version()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> eventCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxPublisher).append(eq(tenant), eq("appr_biz-1"), eq("ApprovalInstance"), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).hasSize(1);
        assertThat(eventCaptor.getValue().get(0)).isInstanceOf(ApprovalInstanceApproved.class);
        verify(workflowEnginePort).completeTask(tenant, "appr_biz-1", "wf_task_1", WorkflowOutcome.APPROVED);

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.APPROVED);
        assertThat(auditCaptor.getValue().instanceId()).isEqualTo("appr_biz-1");
        assertThat(auditCaptor.getValue().taskId()).isEqualTo(task.id());
        assertThat(auditCaptor.getValue().actor()).isEqualTo(assignee);
    }

    @Test
    void doesNotAdvanceInstanceWhileOtherTasksPending() {
        ApprovalTask task = task();
        ApprovalInstance instance = instance();
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));
        engineReturns(new WorkflowStep(List.of(), null));

        ApprovalInstanceDTO dto = handler().handle(command(task));

        assertThat(dto.status()).isEqualTo(ApprovalStatus.RUNNING.name());
        verify(taskRepository).updateStatus(task);
        verify(instanceRepository, never()).save(any());
        verify(outboxPublisher, never()).append(any(), any(), any(), any());
        verify(workflowEnginePort).completeTask(tenant, "appr_biz-1", "wf_task_1", WorkflowOutcome.APPROVED);
    }

    @Test
    void rejectsUnknownTask() {
        when(taskRepository.findById(eq(tenant), eq("task_missing"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler().handle(new ApproveTaskCommand(tenant, "task_missing", assignee, null)))
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
        assertThatThrownBy(() -> handler().handle(new ApproveTaskCommand(tenant, task.id(), stranger, null)))
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

        engineReturns(new WorkflowStep(List.of(), null));

        handler().handle(new ApproveTaskCommand(tenant, task.id(), assignee, "because"));

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository).append(captor.capture());
        assertThat(captor.getValue().comment()).isEqualTo("because");
    }

    @Test
    void opensTheTasksTheEngineSaysComeNext() {
        ApprovalTask task = task();
        ApprovalInstance instance = instance();
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));
        engineReturns(new WorkflowStep(List.of(
                new WorkflowTask("wf_next", "secondApproval", "Second approval", "USER", "u_5")), null));

        ApprovalInstanceDTO dto = handler().handle(command(task));

        assertThat(dto.status()).isEqualTo(ApprovalStatus.RUNNING.name());
        ArgumentCaptor<ApprovalTask> saved = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().assignee()).isEqualTo(new ApproverRef("USER", "u_5"));
        assertThat(saved.getValue().nodeKey()).isEqualTo("secondApproval");
        assertThat(saved.getValue().workflowTaskRef()).isEqualTo("wf_next");
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void followsTheOutcomeTheEngineReports() {
        // The model, not the handler, decides how the run ends: an engine that ends rejected rejects.
        ApprovalTask task = task();
        ApprovalInstance instance = instance();
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance));
        engineReturns(new WorkflowStep(List.of(), WorkflowOutcome.REJECTED));

        ApprovalInstanceDTO dto = handler().handle(command(task));

        assertThat(dto.status()).isEqualTo(ApprovalStatus.REJECTED.name());
    }

    @Test
    void refusesATaskThatIsNotLinkedToAWorkflowStep() {
        ApprovalTask unlinked = ApprovalTask.hydrate(
                "task_old", tenant, "appr_biz-1", "Approve purchase", "USER", "u_2",
                TaskStatus.PENDING, 0L, null, null);
        when(taskRepository.findById(eq(tenant), eq("task_old"))).thenReturn(Optional.of(unlinked));
        when(instanceRepository.findById(eq(tenant), eq(new ApprovalId("appr_biz-1"))))
                .thenReturn(Optional.of(instance()));

        assertThatThrownBy(() -> handler().handle(new ApproveTaskCommand(tenant, "task_old", assignee, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.ILLEGAL_STATE));
        verify(taskRepository, never()).updateStatus(any());
        verify(workflowEnginePort, never()).completeTask(any(), any(), any(), any());
    }
}
