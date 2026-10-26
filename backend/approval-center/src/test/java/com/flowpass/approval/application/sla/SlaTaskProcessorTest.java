package com.flowpass.approval.application.sla;

import com.flowpass.approval.application.command.ParticipantGuard;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.OutboxPublisherPort;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;
import com.flowpass.approval.domain.event.ApprovalTaskEscalated;
import com.flowpass.approval.domain.event.ApprovalTaskOverdue;
import com.flowpass.approval.domain.event.ApprovalTaskReminded;
import com.flowpass.approval.domain.event.DomainEvent;
import com.flowpass.approval.domain.runtime.ApprovalId;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.runtime.ApprovalStatus;
import com.flowpass.approval.domain.sla.OverdueAction;
import com.flowpass.approval.domain.sla.SlaPolicy;
import com.flowpass.approval.domain.sla.SlaPolicyRepository;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Reminders and overdue handling: claimed once, scoped to the tenant, escalated only to an eligible person. */
@ExtendWith(MockitoExtension.class)
class SlaTaskProcessorTest {

    private final TenantId tenant = new TenantId("t_1");
    private final Instant opened = Instant.parse("2026-06-01T09:00:00Z");
    private final Instant due = opened.plus(Duration.ofHours(24));
    private final ApproverRef initiator = new ApproverRef("USER", "u_1");

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ApprovalInstanceRepository instances;
    @Mock
    private SlaPolicyRepository policies;
    @Mock
    private OrgDirectory directory;
    @Mock
    private WorkflowEnginePort engine;
    @Mock
    private AuditRecordRepository audit;
    @Mock
    private OutboxPublisherPort outbox;

    private SlaTaskProcessor processor() {
        return new SlaTaskProcessor(taskRepository, instances, policies, directory,
                new ParticipantGuard(taskRepository), engine, audit, outbox);
    }

    private ApprovalTask task(ApproverRef assignee) {
        ApprovalTask t = ApprovalTask.start(tenant, "appr_1", "Approve", assignee, "approveTask", "wf_1");
        t.scheduleDeadlines(due, due.minus(Duration.ofHours(6)));
        return t;
    }

    private void given(ApprovalTask task, ApprovalStatus status) {
        when(taskRepository.findById(tenant, task.id())).thenReturn(Optional.of(task));
        when(instances.findById(tenant, new ApprovalId("appr_1"))).thenReturn(Optional.of(
                ApprovalInstance.hydrate(new ApprovalId("appr_1"), tenant, "app", "biz-1", "purchase_request",
                        initiator, null, 1, Map.of(), status, 0L)));
    }

    private void policy(OverdueAction action, String user) {
        when(policies.find(tenant, "purchase_request")).thenReturn(Optional.of(
                SlaPolicy.of(tenant, "purchase_request", 24, 6, action, user)));
    }

    @SuppressWarnings("unchecked")
    private List<DomainEvent> publishedEvents(String taskId) {
        ArgumentCaptor<List<DomainEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(outbox).append(eq(tenant), eq(taskId), eq("ApprovalTask"), events.capture());
        return events.getValue();
    }

    // ---- reminders -------------------------------------------------------------------------------------

    @Test
    void aReminderIsSentOnceItsClaimIsWon() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        Instant now = due.minus(Duration.ofHours(5));
        when(taskRepository.claimReminder(tenant, t.id(), now)).thenReturn(true);
        when(taskRepository.findById(tenant, t.id())).thenReturn(Optional.of(t));

        assertThat(processor().remind(tenant, t.id(), now)).isTrue();

        assertThat(publishedEvents(t.id())).singleElement().isInstanceOfSatisfying(ApprovalTaskReminded.class, e -> {
            assertThat(e.aggregateId()).isEqualTo(t.id());
            assertThat(e.assigneeId()).isEqualTo("u_2");
            assertThat(e.dueAt()).isEqualTo(due);
            assertThat(e.tenantId()).isEqualTo("t_1");
        });
    }

    @Test
    void aReminderAnotherWorkerAlreadyClaimedIsNotSentAgain() {
        when(taskRepository.claimReminder(any(), any(), any())).thenReturn(false);

        assertThat(processor().remind(tenant, "task_x", due)).isFalse();

        verifyNoInteractions(outbox);
        verify(taskRepository, never()).findById(any(), any());
    }

    @Test
    void noReminderIsSentOnceTheDeadlineHasAlreadyPassed() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        when(taskRepository.claimReminder(tenant, t.id(), due.plusSeconds(1))).thenReturn(true);
        when(taskRepository.findById(tenant, t.id())).thenReturn(Optional.of(t));

        assertThat(processor().remind(tenant, t.id(), due.plusSeconds(1))).isFalse();

        verifyNoInteractions(outbox);
    }

    // ---- overdue: notify only --------------------------------------------------------------------------

    @Test
    void notifyOnlyEmitsTheOverdueEventAndAuditsButMovesNothing() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        given(t, ApprovalStatus.RUNNING);
        policy(OverdueAction.NOTIFY_ONLY, null);
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.NOTIFIED);

        assertThat(publishedEvents(t.id())).singleElement().isInstanceOf(ApprovalTaskOverdue.class);
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).append(record.capture());
        assertThat(record.getValue().action()).isEqualTo(AuditAction.OVERDUE);
        assertThat(record.getValue().comment()).isEqualTo("overdue");
        assertThat(record.getValue().actor()).isEqualTo(new ApproverRef("SYSTEM", "sla"));
        assertThat(t.assignee().id()).isEqualTo("u_2");
        verify(taskRepository, never()).updateAssignee(any());
        verifyNoInteractions(engine);
    }

    // ---- overdue: escalate to the manager --------------------------------------------------------------

    @Test
    void anOverdueTaskIsHandedToTheAssigneesManagerEverywhere() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        given(t, ApprovalStatus.RUNNING);
        policy(OverdueAction.ESCALATE_TO_MANAGER, null);
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);
        when(directory.managerOf(tenant, "u_2")).thenReturn(Optional.of("u_boss"));

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.ESCALATED);

        assertThat(t.assignee()).isEqualTo(new ApproverRef("USER", "u_boss"));
        verify(taskRepository).updateAssignee(t);
        verify(engine).reassign(tenant, "appr_1", "wf_1", new CandidateApprover("USER", "u_boss"));
        List<DomainEvent> events = publishedEvents(t.id());
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ApprovalTaskOverdue.class);
        assertThat(events.get(1)).isInstanceOfSatisfying(ApprovalTaskEscalated.class, e -> {
            assertThat(e.fromAssigneeId()).isEqualTo("u_2");
            assertThat(e.toUserId()).isEqualTo("u_boss");
        });
        ArgumentCaptor<AuditRecord> records = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit, org.mockito.Mockito.times(2)).append(records.capture());
        assertThat(records.getAllValues()).extracting(AuditRecord::action)
                .containsExactly(AuditAction.ESCALATED, AuditAction.OVERDUE);
        assertThat(records.getAllValues().get(0).comment()).isEqualTo("USER:u_2 -> u_boss");
    }

    @Test
    void withoutAManagerTheFallbackUserIsUsed() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        given(t, ApprovalStatus.RUNNING);
        policy(OverdueAction.ESCALATE_TO_MANAGER, "u_cfo");
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);
        when(directory.managerOf(tenant, "u_2")).thenReturn(Optional.empty());

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.ESCALATED);

        assertThat(t.assignee().id()).isEqualTo("u_cfo");
    }

    @Test
    void aRoleTaskHasNoManagerSoOnlyTheFallbackUserCanTakeIt() {
        ApprovalTask t = task(new ApproverRef("ROLE", "finance"));
        given(t, ApprovalStatus.RUNNING);
        policy(OverdueAction.ESCALATE_TO_MANAGER, "u_cfo");
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.ESCALATED);

        assertThat(t.assignee()).isEqualTo(new ApproverRef("USER", "u_cfo"));
        verifyNoInteractions(directory);
    }

    @Test
    void nobodyToEscalateToIsOnlyNotifiedAndSaysWhy() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        given(t, ApprovalStatus.RUNNING);
        policy(OverdueAction.ESCALATE_TO_MANAGER, null);
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);
        when(directory.managerOf(tenant, "u_2")).thenReturn(Optional.empty());

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.NOTIFIED);

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).append(record.capture());
        assertThat(record.getValue().comment()).contains("not escalated").contains("no manager is recorded");
        assertThat(t.assignee().id()).isEqualTo("u_2");
        verifyNoInteractions(engine);
    }

    // ---- overdue: escalate to a named user -------------------------------------------------------------

    @Test
    void escalateToUserGoesStraightToThatUser() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        given(t, ApprovalStatus.RUNNING);
        policy(OverdueAction.ESCALATE_TO_USER, "u_cfo");
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.ESCALATED);

        assertThat(t.assignee().id()).isEqualTo("u_cfo");
        verifyNoInteractions(directory);
    }

    @Test
    void theInitiatorOrSomeoneAlreadyInvolvedIsNeverEscalatedTo() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        given(t, ApprovalStatus.RUNNING);
        policy(OverdueAction.ESCALATE_TO_USER, "u_1");
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.NOTIFIED);

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).append(record.capture());
        assertThat(record.getValue().comment()).contains("u_1 cannot take the task").contains("own request");
        assertThat(t.assignee().id()).isEqualTo("u_2");
        verify(taskRepository, never()).updateAssignee(any());
    }

    @Test
    void someoneAlreadyInvolvedIsSkippedLikeTheInitiator() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        given(t, ApprovalStatus.RUNNING);
        policy(OverdueAction.ESCALATE_TO_MANAGER, null);
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);
        when(directory.managerOf(tenant, "u_2")).thenReturn(Optional.of("u_boss"));
        when(taskRepository.existsUserTask(tenant, "appr_1", "u_boss")).thenReturn(true);

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.NOTIFIED);

        assertThat(t.assignee().id()).isEqualTo("u_2");
    }

    // ---- guard rails -----------------------------------------------------------------------------------

    @Test
    void anOverdueClaimLostToAnotherWorkerDoesNothing() {
        when(taskRepository.claimOverdue(tenant, "task_x", due)).thenReturn(false);

        assertThat(processor().handleOverdue(tenant, "task_x", due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.NONE);

        verifyNoInteractions(outbox, audit, engine);
    }

    @Test
    void aFinishedRequestIsLeftAlone() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        given(t, ApprovalStatus.WITHDRAWN);
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.NONE);

        verifyNoInteractions(outbox, audit, engine);
    }

    @Test
    void aTemplateThatLostItsPolicyStillGetsTheOverdueNotification() {
        ApprovalTask t = task(new ApproverRef("USER", "u_2"));
        given(t, ApprovalStatus.RUNNING);
        when(policies.find(tenant, "purchase_request")).thenReturn(Optional.empty());
        when(taskRepository.claimOverdue(tenant, t.id(), due)).thenReturn(true);

        assertThat(processor().handleOverdue(tenant, t.id(), due)).isEqualTo(SlaTaskProcessor.OverdueOutcome.NOTIFIED);

        assertThat(publishedEvents(t.id())).singleElement().isInstanceOf(ApprovalTaskOverdue.class);
        verifyNoInteractions(engine);
    }
}
