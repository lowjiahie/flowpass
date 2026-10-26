package com.flowpass.approval.application.command;

import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowTask;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.delegation.Delegation;
import com.flowpass.approval.domain.delegation.DelegationRepository;
import com.flowpass.approval.domain.runtime.ApprovalId;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.runtime.ApprovalStatus;
import com.flowpass.approval.domain.sla.OverdueAction;
import com.flowpass.approval.domain.sla.SlaPolicy;
import com.flowpass.approval.domain.sla.SlaPolicyRepository;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import com.flowpass.approval.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Delegation and deadlines are decided at the moment a task opens. */
@ExtendWith(MockitoExtension.class)
class WorkflowTaskMaterializerTest {

    private final TenantId tenant = new TenantId("t_1");
    private final Instant now = Instant.parse("2026-06-01T09:00:00Z");
    private final MutableClock clock = new MutableClock(now);
    private final ApproverRef initiator = new ApproverRef("USER", "u_1");

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private DelegationRepository delegations;
    @Mock
    private ApprovalInstanceRepository instances;
    @Mock
    private SlaPolicyRepository slaPolicies;
    @Mock
    private WorkflowEnginePort engine;
    @Mock
    private AuditRecordRepository audit;

    private WorkflowTaskMaterializer materializer() {
        return new WorkflowTaskMaterializer(taskRepository, delegations, instances, slaPolicies, engine, audit, clock);
    }

    private void instanceExists() {
        when(instances.findById(tenant, new ApprovalId("appr_1"))).thenReturn(Optional.of(
                ApprovalInstance.hydrate(new ApprovalId("appr_1"), tenant, "app", "biz-1", "purchase_request",
                        initiator, null, 1, Map.of(), ApprovalStatus.RUNNING, 0L)));
    }

    private static WorkflowTask wf(String ref, String type, String id) {
        return new WorkflowTask(ref, "approveTask", "Approve", type, id);
    }

    private Delegation delegation(String from, String to) {
        return Delegation.create(tenant, from, to, null, now.plus(Duration.ofDays(7)), now);
    }

    @BeforeEach
    void noPolicyByDefault() {
        org.mockito.Mockito.lenient().when(slaPolicies.find(any(), any())).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(delegations.findActive(any(), any(), any())).thenReturn(Optional.empty());
    }

    // ---- delegation ------------------------------------------------------------------------------------

    @Test
    void aTaskForSomeoneOnDelegationGoesToTheirDelegate() {
        instanceExists();
        Delegation d = delegation("u_2", "u_9");
        when(delegations.findActive(tenant, "u_2", now)).thenReturn(Optional.of(d));

        List<ApprovalTask> created = materializer().materialize(tenant, "appr_1", List.of(wf("wf_1", "USER", "u_2")));

        assertThat(created).singleElement().satisfies(t -> assertThat(t.assignee()).isEqualTo(new ApproverRef("USER", "u_9")));
        verify(engine).reassign(tenant, "appr_1", "wf_1", new CandidateApprover("USER", "u_9"));
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).append(record.capture());
        assertThat(record.getValue().action()).isEqualTo(AuditAction.DELEGATED);
        assertThat(record.getValue().actor()).isEqualTo(new ApproverRef("SYSTEM", "delegation"));
        assertThat(record.getValue().comment()).isEqualTo("USER:u_2 -> u_9 (delegation " + d.id() + ")");
    }

    @Test
    void theDelegateNeverIsTheInitiator() {
        instanceExists();
        when(delegations.findActive(tenant, "u_2", now)).thenReturn(Optional.of(delegation("u_2", "u_1")));

        List<ApprovalTask> created = materializer().materialize(tenant, "appr_1", List.of(wf("wf_1", "USER", "u_2")));

        assertThat(created.get(0).assignee().id()).as("the task stays with its assignee").isEqualTo("u_2");
        verify(engine, never()).reassign(any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void aDelegateAlreadyInvolvedInTheRequestIsNotGivenASecondTask() {
        instanceExists();
        when(delegations.findActive(tenant, "u_2", now)).thenReturn(Optional.of(delegation("u_2", "u_9")));
        when(taskRepository.existsUserTask(tenant, "appr_1", "u_9")).thenReturn(true);

        List<ApprovalTask> created = materializer().materialize(tenant, "appr_1", List.of(wf("wf_1", "USER", "u_2")));

        assertThat(created.get(0).assignee().id()).isEqualTo("u_2");
        verify(engine, never()).reassign(any(), any(), any(), any());
    }

    @Test
    void aDelegateWhoIsAlsoAnApproverOfTheSameBatchIsNotDoubled() {
        instanceExists();
        when(delegations.findActive(tenant, "u_2", now)).thenReturn(Optional.of(delegation("u_2", "u_3")));

        List<ApprovalTask> created = materializer().materialize(tenant, "appr_1",
                List.of(wf("wf_1", "USER", "u_2"), wf("wf_2", "USER", "u_3")));

        assertThat(created).extracting(t -> t.assignee().id()).containsExactly("u_2", "u_3");
    }

    @Test
    void twoPeopleDelegatingToTheSamePersonGiveThemOnlyOneTask() {
        instanceExists();
        when(delegations.findActive(tenant, "u_2", now)).thenReturn(Optional.of(delegation("u_2", "u_9")));
        when(delegations.findActive(tenant, "u_3", now)).thenReturn(Optional.of(delegation("u_3", "u_9")));

        List<ApprovalTask> created = materializer().materialize(tenant, "appr_1",
                List.of(wf("wf_1", "USER", "u_2"), wf("wf_2", "USER", "u_3")));

        assertThat(created).extracting(t -> t.assignee().id()).containsExactly("u_9", "u_3");
    }

    @Test
    void roleTasksAreNeverDelegated() {
        instanceExists();

        List<ApprovalTask> created = materializer().materialize(tenant, "appr_1", List.of(wf("wf_1", "ROLE", "finance")));

        assertThat(created.get(0).assignee()).isEqualTo(new ApproverRef("ROLE", "finance"));
        verifyNoInteractions(delegations);
    }

    @Test
    void withoutAnActiveDelegationNothingChanges() {
        instanceExists();

        List<ApprovalTask> created = materializer().materialize(tenant, "appr_1", List.of(wf("wf_1", "USER", "u_2")));

        assertThat(created.get(0).assignee().id()).isEqualTo("u_2");
        verify(engine, never()).reassign(any(), any(), any(), any());
    }

    // ---- deadlines -------------------------------------------------------------------------------------

    @Test
    void theTemplatesSlaGivesTheTaskItsDeadlines() {
        instanceExists();
        when(slaPolicies.find(tenant, "purchase_request")).thenReturn(Optional.of(
                SlaPolicy.of(tenant, "purchase_request", 48, 6, OverdueAction.NOTIFY_ONLY, null)));

        List<ApprovalTask> created = materializer().materialize(tenant, "appr_1", List.of(wf("wf_1", "USER", "u_2")));

        assertThat(created.get(0).dueAt()).isEqualTo(now.plus(Duration.ofHours(48)));
        assertThat(created.get(0).remindAt()).isEqualTo(now.plus(Duration.ofHours(42)));
    }

    @Test
    void deadlinesAreCountedFromWhenEachTaskActuallyOpens() {
        instanceExists();
        when(slaPolicies.find(tenant, "purchase_request")).thenReturn(Optional.of(
                SlaPolicy.of(tenant, "purchase_request", 24, null, OverdueAction.NOTIFY_ONLY, null)));
        clock.advance(Duration.ofDays(3));

        List<ApprovalTask> created = materializer().materialize(tenant, "appr_1", List.of(wf("wf_1", "USER", "u_2")));

        assertThat(created.get(0).dueAt()).isEqualTo(now.plus(Duration.ofDays(3)).plus(Duration.ofHours(24)));
        assertThat(created.get(0).remindAt()).isNull();
    }

    @Test
    void withoutAnSlaThereIsNoDeadline() {
        instanceExists();

        assertThat(materializer().materialize(tenant, "appr_1", List.of(wf("wf_1", "USER", "u_2"))).get(0).dueAt()).isNull();
    }

    // ---- plain mirror ------------------------------------------------------------------------------------

    @Test
    void anEngineTaskWithoutAnAssigneeBecomesNoBusinessTask() {
        assertThat(materializer().materialize(tenant, "appr_1", List.of(new WorkflowTask("wf_m", "manualReview", "Manual", null, null))))
                .isEmpty();
        verifyNoInteractions(instances, slaPolicies, delegations);
    }

    @Test
    void theSimpleConstructorMirrorsTasksWithoutDelegationOrDeadlines() {
        WorkflowTaskMaterializer plain = new WorkflowTaskMaterializer(taskRepository);

        List<ApprovalTask> created = plain.materialize(tenant, "appr_1", List.of(wf("wf_1", "USER", "u_2")));

        assertThat(created.get(0).assignee().id()).isEqualTo("u_2");
        assertThat(created.get(0).dueAt()).isNull();
        verify(taskRepository).save(created.get(0));
    }
}
