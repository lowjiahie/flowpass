package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowStep;
import com.flowpass.approval.application.port.WorkflowTask;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.contract.dto.ApprovalTaskDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Use-case tests for transfer, add-sign and return: authorization, eligibility, ordering, and "nothing changes on refusal". */
@ExtendWith(MockitoExtension.class)
class TaskActionsHandlersTest {

    private final TenantId tenant = new TenantId("t_1");
    private final ApproverRef initiator = new ApproverRef("USER", "u_1");
    private final ApproverRef assignee = new ApproverRef("USER", "u_2");
    private final ApprovalId instanceId = new ApprovalId("appr_1");

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ApprovalInstanceRepository instanceRepository;
    @Mock
    private WorkflowEnginePort engine;
    @Mock
    private AuditRecordRepository audit;
    @Mock
    private OrgDirectory orgDirectory;

    private TaskActionAuthorizer authorizer() {
        return new TaskActionAuthorizer(orgDirectory);
    }

    private ParticipantGuard guard() {
        return new ParticipantGuard(taskRepository);
    }

    private ApprovalInstance instance(ApprovalStatus status) {
        return ApprovalInstance.hydrate(instanceId, tenant, "app", "biz-1", "purchase_request", initiator, null, 1,
                Map.of(), status, 0L);
    }

    private ApprovalTask task(ApproverRef who, String ref) {
        return ApprovalTask.start(tenant, "appr_1", "Approve", who, "approveTask", ref);
    }

    private ApprovalTask given(ApprovalTask task) {
        when(taskRepository.findById(tenant, task.id())).thenReturn(Optional.of(task));
        when(instanceRepository.findById(tenant, instanceId)).thenReturn(Optional.of(instance(ApprovalStatus.RUNNING)));
        return task;
    }

    private static void assertCode(Throwable ex, BusinessErrorCode code) {
        assertThat(ex).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---- participant guard ---------------------------------------------------------------------------

    @Test
    void anEligibleUserIsAcceptedAndTrimmed() {
        assertThat(guard().requireNewParticipant(instance(ApprovalStatus.RUNNING), "  u_9 "))
                .isEqualTo(new ApproverRef("USER", "u_9"));
    }

    @Test
    void theInitiatorBlankAndAlreadyInvolvedUsersAreNotEligible() {
        ApprovalInstance running = instance(ApprovalStatus.RUNNING);
        when(taskRepository.existsUserTask(tenant, "appr_1", "u_3")).thenReturn(true);

        assertThatThrownBy(() -> guard().requireNewParticipant(running, "u_1"))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED)).hasMessageContaining("own request");
        assertThatThrownBy(() -> guard().requireNewParticipant(running, "u_3"))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED)).hasMessageContaining("already involved");
        assertThatThrownBy(() -> guard().requireNewParticipant(running, "   "))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> guard().requireNewParticipant(running, null))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> guard().requireNewParticipant(running, "x".repeat(129)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED));
    }

    @Test
    void nobodyCanBeAddedToAFinishedRequest() {
        assertThatThrownBy(() -> guard().requireNewParticipant(instance(ApprovalStatus.APPROVED), "u_9"))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE));
    }

    // ---- transfer ------------------------------------------------------------------------------------

    private TransferTaskHandler transferHandler() {
        return new TransferTaskHandler(taskRepository, instanceRepository, engine, audit, authorizer(), guard());
    }

    @Test
    void transferChangesTheAssigneeInTheEngineToo() {
        ApprovalTask task = given(task(assignee, "wf_1"));

        ApprovalTaskDTO dto = transferHandler().handle(new TransferTaskCommand(tenant, task.id(), assignee, "u_9", "on leave"));

        assertThat(dto.assigneeId()).isEqualTo("u_9");
        assertThat(dto.status()).isEqualTo("PENDING");
        assertThat(task.version()).isEqualTo(1);
        verify(taskRepository).updateAssignee(task);
        verify(engine).reassign(tenant, "appr_1", "wf_1", new CandidateApprover("USER", "u_9"));
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).append(record.capture());
        assertThat(record.getValue().action()).isEqualTo(AuditAction.TRANSFERRED);
        assertThat(record.getValue().actor()).isEqualTo(assignee);
        assertThat(record.getValue().taskId()).isEqualTo(task.id());
        assertThat(record.getValue().comment()).isEqualTo("USER:u_2 -> u_9: on leave");
    }

    @Test
    void aRoleMemberCanTransferARoleTaskToOneUser() {
        ApprovalTask task = given(task(new ApproverRef("ROLE", "finance"), "wf_1"));
        when(orgDirectory.rolesOf(tenant, "u_5")).thenReturn(Set.of("finance"));

        ApprovalTaskDTO dto = transferHandler().handle(
                new TransferTaskCommand(tenant, task.id(), new ApproverRef("USER", "u_5"), "u_9", null));

        assertThat(dto.assigneeType()).isEqualTo("USER");
        assertThat(dto.assigneeId()).isEqualTo("u_9");
    }

    @Test
    void onlyTheAssigneeMayTransferAndNothingChangesOtherwise() {
        ApprovalTask task = given(task(assignee, "wf_1"));

        assertThatThrownBy(() -> transferHandler().handle(
                new TransferTaskCommand(tenant, task.id(), new ApproverRef("USER", "u_7"), "u_9", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.FORBIDDEN));

        assertThat(task.assignee()).isEqualTo(assignee);
        verify(taskRepository, never()).updateAssignee(any());
        verifyNoInteractions(engine, audit);
    }

    @Test
    void transferringToTheInitiatorIsRefusedBeforeAnythingChanges() {
        ApprovalTask task = given(task(assignee, "wf_1"));

        assertThatThrownBy(() -> transferHandler().handle(new TransferTaskCommand(tenant, task.id(), assignee, "u_1", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED));

        verify(taskRepository, never()).updateAssignee(any());
        verifyNoInteractions(engine, audit);
    }

    @Test
    void transferringToTheCurrentAssigneeIsAnIllegalState() {
        ApprovalTask task = given(task(assignee, "wf_1"));

        assertThatThrownBy(() -> transferHandler().handle(new TransferTaskCommand(tenant, task.id(), assignee, "u_2", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE));
        verifyNoInteractions(engine);
    }

    @Test
    void aFinishedTaskCannotBeTransferred() {
        ApprovalTask task = given(task(assignee, "wf_1"));
        task.approve();

        assertThatThrownBy(() -> transferHandler().handle(new TransferTaskCommand(tenant, task.id(), assignee, "u_9", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE));
        verifyNoInteractions(engine);
    }

    @Test
    void anEngineRefusalIsAnIllegalState() {
        ApprovalTask task = given(task(assignee, "wf_1"));
        org.mockito.Mockito.doThrow(new IllegalStateException("No active task wf_1"))
                .when(engine).reassign(any(), any(), any(), any());

        assertThatThrownBy(() -> transferHandler().handle(new TransferTaskCommand(tenant, task.id(), assignee, "u_9", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE));
        verifyNoInteractions(audit);
    }

    @Test
    void transferringAnUnknownTaskIsNotFound() {
        when(taskRepository.findById(tenant, "task_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferHandler().handle(new TransferTaskCommand(tenant, "task_x", assignee, "u_9", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.TASK_NOT_FOUND));
    }

    // ---- add approver --------------------------------------------------------------------------------

    private AddApproverHandler addHandler() {
        return new AddApproverHandler(taskRepository, instanceRepository, engine, audit, authorizer(), guard(),
                new WorkflowTaskMaterializer(taskRepository));
    }

    @Test
    void addingAnApproverMirrorsTheTaskTheEngineOpened() {
        ApprovalTask task = given(task(assignee, "wf_1"));
        when(engine.addApprover(tenant, "appr_1", "wf_1", new CandidateApprover("USER", "u_9"))).thenReturn(
                new WorkflowStep(List.of(new WorkflowTask("wf_2", "approveTask", "Approve", "USER", "u_9")), null));

        ApprovalTaskDTO dto = addHandler().handle(new AddApproverCommand(tenant, task.id(), assignee, "u_9", "need legal"));

        assertThat(dto.assigneeId()).isEqualTo("u_9");
        assertThat(dto.status()).isEqualTo("PENDING");
        ArgumentCaptor<ApprovalTask> saved = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().workflowTaskRef()).isEqualTo("wf_2");
        assertThat(saved.getValue().nodeKey()).isEqualTo("approveTask");
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).append(record.capture());
        assertThat(record.getValue().action()).isEqualTo(AuditAction.APPROVER_ADDED);
        assertThat(record.getValue().taskId()).as("recorded against the task that asked").isEqualTo(task.id());
        assertThat(record.getValue().comment()).isEqualTo("added u_9: need legal");
    }

    @Test
    void onlyAnApproverOfTheTaskCanAddAnotherAndNothingChangesOtherwise() {
        ApprovalTask task = given(task(assignee, "wf_1"));

        assertThatThrownBy(() -> addHandler().handle(
                new AddApproverCommand(tenant, task.id(), new ApproverRef("USER", "u_7"), "u_9", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.FORBIDDEN));
        verifyNoInteractions(engine, audit);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void anIneligibleNewApproverIsRefusedBeforeTheEngineIsTouched() {
        ApprovalTask task = given(task(assignee, "wf_1"));

        assertThatThrownBy(() -> addHandler().handle(new AddApproverCommand(tenant, task.id(), assignee, "u_1", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(engine);
    }

    @Test
    void aStepThatCannotTakeMoreApproversIsAnIllegalStateAndSavesNothing() {
        ApprovalTask task = given(task(assignee, "wf_1"));
        when(engine.addApprover(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("The step 'manualReview' cannot take more approvers"));

        assertThatThrownBy(() -> addHandler().handle(new AddApproverCommand(tenant, task.id(), assignee, "u_9", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE)).hasMessageContaining("cannot take more");
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void aRequestWithTooManyOpenTasksCannotGrowFurther() {
        ApprovalTask task = given(task(assignee, "wf_1"));
        List<ApprovalTask> many = java.util.stream.IntStream.range(0, AddApproverHandler.MAX_OPEN_TASKS)
                .mapToObj(i -> task(new ApproverRef("USER", "x" + i), "wf_x" + i)).toList();
        when(taskRepository.findOpenByInstance(tenant, "appr_1")).thenReturn(many);

        assertThatThrownBy(() -> addHandler().handle(new AddApproverCommand(tenant, task.id(), assignee, "u_9", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED)).hasMessageContaining("at most");
        verifyNoInteractions(engine);
    }

    @Test
    void aFinishedTaskCannotAddAnApprover() {
        ApprovalTask task = given(task(assignee, "wf_1"));
        task.reject();

        assertThatThrownBy(() -> addHandler().handle(new AddApproverCommand(tenant, task.id(), assignee, "u_9", null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE));
        verifyNoInteractions(engine);
    }

    // ---- return --------------------------------------------------------------------------------------

    private ReturnTaskHandler returnHandler() {
        return new ReturnTaskHandler(taskRepository, instanceRepository, engine, audit, authorizer(),
                new WorkflowTaskMaterializer(taskRepository));
    }

    @Test
    void returningClosesMyTaskCancelsTheRestOfTheRoundAndOpensThePreviousRoundAgain() {
        ApprovalTask mine = given(task(assignee, "wf_5"));
        ApprovalTask sibling = task(new ApproverRef("USER", "u_6"), "wf_6");
        when(taskRepository.findOpenByInstance(tenant, "appr_1")).thenReturn(List.of(mine, sibling));
        when(engine.returnToPreviousRound(tenant, "appr_1", "wf_5")).thenReturn(new WorkflowStep(List.of(
                new WorkflowTask("wf_a", "approveTask", "Approve (first round)", "USER", "u_a"),
                new WorkflowTask("wf_b", "approveTask", "Approve (first round)", "USER", "u_b")), null));

        ApprovalInstanceDTO dto = returnHandler().handle(new ReturnTaskCommand(tenant, mine.id(), assignee, "missing quote"));

        assertThat(dto.status()).isEqualTo("RUNNING");
        assertThat(mine.status()).isEqualTo(TaskStatus.RETURNED);
        assertThat(sibling.status()).isEqualTo(TaskStatus.CANCELLED);
        ArgumentCaptor<ApprovalTask> created = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository, org.mockito.Mockito.times(2)).save(created.capture());
        assertThat(created.getAllValues()).extracting(t -> t.assignee().id()).containsExactly("u_a", "u_b");
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).append(record.capture());
        assertThat(record.getValue().action()).isEqualTo(AuditAction.RETURNED);
        assertThat(record.getValue().comment()).isEqualTo("missing quote");
    }

    @Test
    void theDiscardedRoundIsClosedBeforeTheReopenedOneIsCreated() {
        ApprovalTask mine = given(task(assignee, "wf_5"));
        ApprovalTask sibling = task(new ApproverRef("USER", "u_6"), "wf_6");
        when(taskRepository.findOpenByInstance(tenant, "appr_1")).thenReturn(List.of(mine, sibling));
        when(engine.returnToPreviousRound(any(), any(), any())).thenReturn(new WorkflowStep(
                List.of(new WorkflowTask("wf_a", "approveTask", "Approve", "USER", "u_a")), null));

        returnHandler().handle(new ReturnTaskCommand(tenant, mine.id(), assignee, null));

        InOrder order = inOrder(taskRepository);
        order.verify(taskRepository).updateStatus(sibling);
        order.verify(taskRepository).save(any());
    }

    @Test
    void aRefusedReturnLeavesNoTraceInTheBusinessView() {
        ApprovalTask mine = given(task(assignee, "wf_5"));
        when(taskRepository.findOpenByInstance(tenant, "appr_1")).thenReturn(List.of(mine));
        when(engine.returnToPreviousRound(tenant, "appr_1", "wf_5"))
                .thenThrow(new IllegalStateException("There is no earlier round to return to"));

        assertThatThrownBy(() -> returnHandler().handle(new ReturnTaskCommand(tenant, mine.id(), assignee, null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE)).hasMessageContaining("no earlier round");

        // The handler never got as far as closing others or creating tasks; the transaction undoes the task update.
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void onlyAnApproverOfTheTaskCanReturnIt() {
        ApprovalTask mine = given(task(assignee, "wf_5"));

        assertThatThrownBy(() -> returnHandler().handle(
                new ReturnTaskCommand(tenant, mine.id(), new ApproverRef("USER", "u_7"), null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.FORBIDDEN));
        verifyNoInteractions(engine, audit);
        assertThat(mine.status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void aFinishedTaskCannotBeReturned() {
        ApprovalTask mine = given(task(assignee, "wf_5"));
        mine.approve();
        when(taskRepository.findOpenByInstance(tenant, "appr_1")).thenReturn(List.of());

        assertThatThrownBy(() -> returnHandler().handle(new ReturnTaskCommand(tenant, mine.id(), assignee, null)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE));
        verify(engine, never()).returnToPreviousRound(any(), any(), eq("wf_5"));
    }
}
