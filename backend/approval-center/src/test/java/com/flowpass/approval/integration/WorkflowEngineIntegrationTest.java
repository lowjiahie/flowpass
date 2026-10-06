package com.flowpass.approval.integration;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowOutcome;
import com.flowpass.approval.application.port.WorkflowStep;
import com.flowpass.approval.application.port.WorkflowTask;
import com.flowpass.approval.domain.common.TenantId;
import org.flowable.engine.RepositoryService;
import com.flowpass.approval.support.TestBpmn;
import com.flowpass.approval.support.EngineTestSupport;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The process engine as the source of truth for routing, exercised through {@link WorkflowEnginePort}
 * against a real Flowable engine and PostgreSQL: which tasks it opens, when and how a run ends, and that
 * deployments and runs are tenant-scoped.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class WorkflowEngineIntegrationTest {

    private static final TenantId TENANT = new TenantId("t_engine");
    private static final String KEY = "purchase_request";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WorkflowEnginePort engine;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        EngineTestSupport.clearRuns(runtimeService);
        jdbc.execute("TRUNCATE approval_workflow_ref, approval_template CASCADE");
        // The engine runs the stored, published definition — seed one per tenant.
        TestBpmn.seedPublished(jdbc, TENANT.value(), KEY, 1);
        TestBpmn.seedPublished(jdbc, "t_engine_other", KEY, 1);
    }

    private static List<CandidateApprover> approvers(String... ids) {
        List<CandidateApprover> list = new ArrayList<>();
        for (String id : ids) {
            list.add(new CandidateApprover("USER", id));
        }
        return list;
    }

    private String newInstanceId() {
        return "appr_engine_" + java.util.UUID.randomUUID();
    }

    private List<String> endEventsReached(String instanceId) {
        return jdbc.queryForList("""
                SELECT ACT_ID_ FROM ACT_HI_ACTINST
                WHERE ACT_TYPE_ = 'endEvent' AND PROC_INST_ID_ IN
                      (SELECT ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_ = ?)
                """, String.class, instanceId);
    }

    private WorkflowStep start(TenantId tenant, String instanceId, String... approverIds) {
        return engine.start(tenant, instanceId, KEY, 1, Map.of("amount", 100), approvers(approverIds));
    }

    private static WorkflowTask taskOf(WorkflowStep step, String assigneeId) {
        return step.newTasks().stream().filter(t -> assigneeId.equals(t.assigneeId())).findFirst().orElseThrow();
    }

    @Test
    void opensOneEngineTaskPerApproverAssignedToThem() {
        WorkflowStep step = start(TENANT, newInstanceId(), "u_2", "u_3");

        assertThat(step.finished()).isFalse();
        assertThat(step.newTasks()).hasSize(2);
        assertThat(step.newTasks()).extracting(WorkflowTask::assigneeType).containsOnly("USER");
        assertThat(step.newTasks()).extracting(WorkflowTask::assigneeId).containsExactlyInAnyOrder("u_2", "u_3");
        assertThat(step.newTasks()).extracting(WorkflowTask::nodeKey).containsOnly("approveTask");
        assertThat(step.newTasks()).extracting(WorkflowTask::ref).doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    void theRunEndsApprovedOnlyWhenEveryApproverApproved() {
        String instanceId = newInstanceId();
        WorkflowStep started = start(TENANT, instanceId, "u_2", "u_3");

        WorkflowStep afterFirst = engine.completeTask(TENANT, instanceId, taskOf(started, "u_2").ref(),
                WorkflowOutcome.APPROVED);
        assertThat(afterFirst.finished()).isFalse();
        assertThat(afterFirst.newTasks()).isEmpty();

        WorkflowStep afterSecond = engine.completeTask(TENANT, instanceId, taskOf(started, "u_3").ref(),
                WorkflowOutcome.APPROVED);
        assertThat(afterSecond.finished()).isTrue();
        assertThat(afterSecond.outcome()).isEqualTo(WorkflowOutcome.APPROVED);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(instanceId).count()).isZero();
    }

    @Test
    void oneRejectionEndsTheRunRejectedAndTheEngineRemovesTheSiblings() {
        String instanceId = newInstanceId();
        WorkflowStep started = start(TENANT, instanceId, "u_2", "u_3", "u_4");

        WorkflowStep afterReject = engine.completeTask(TENANT, instanceId, taskOf(started, "u_3").ref(),
                WorkflowOutcome.REJECTED);

        assertThat(afterReject.finished()).isTrue();
        assertThat(afterReject.outcome()).isEqualTo(WorkflowOutcome.REJECTED);
        assertThat(taskService.createTaskQuery().processInstanceBusinessKey(instanceId).count()).isZero();
    }

    @Test
    void aRejectionAfterSomeApproversAlreadyApprovedStillEndsTheRunRejected() {
        String instanceId = newInstanceId();
        WorkflowStep started = start(TENANT, instanceId, "u_2", "u_3", "u_4");
        engine.completeTask(TENANT, instanceId, taskOf(started, "u_2").ref(), WorkflowOutcome.APPROVED);

        WorkflowStep afterReject = engine.completeTask(TENANT, instanceId, taskOf(started, "u_3").ref(),
                WorkflowOutcome.REJECTED);

        assertThat(afterReject.finished()).isTrue();
        assertThat(afterReject.outcome()).isEqualTo(WorkflowOutcome.REJECTED);
        assertThat(taskService.createTaskQuery().processInstanceBusinessKey(instanceId).count()).isZero();
    }

    @Test
    void anApprovalAfterTheRunEndedIsRefused() {
        String instanceId = newInstanceId();
        WorkflowStep started = start(TENANT, instanceId, "u_2", "u_3");
        engine.completeTask(TENANT, instanceId, taskOf(started, "u_2").ref(), WorkflowOutcome.REJECTED);

        assertThatThrownBy(() -> engine.completeTask(TENANT, instanceId, taskOf(started, "u_3").ref(),
                WorkflowOutcome.APPROVED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nobodyResolvedParksTheRunOnAnUnassignedTaskInsteadOfApproving() {
        WorkflowStep step = start(TENANT, newInstanceId());

        assertThat(step.finished()).as("no approver must never mean approved").isFalse();
        assertThat(step.newTasks()).singleElement().satisfies(task -> {
            assertThat(task.nodeKey()).isEqualTo("manualReview");
            assertThat(task.assigned()).isFalse();
        });
    }

    @Test
    void aRunCanBeTerminatedAfterSomeApproversAlreadyApproved() {
        String instanceId = newInstanceId();
        WorkflowStep started = start(TENANT, instanceId, "u_2", "u_3");
        engine.completeTask(TENANT, instanceId, taskOf(started, "u_2").ref(), WorkflowOutcome.APPROVED);

        engine.terminate(TENANT, instanceId);

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(instanceId).count()).isZero();
        assertThat(taskService.createTaskQuery().processInstanceBusinessKey(instanceId).count()).isZero();
    }

    @Test
    void aRunParkedOnManualReviewCanBeTerminatedWithoutApprovingIt() {
        String instanceId = newInstanceId();
        start(TENANT, instanceId);

        engine.terminate(TENANT, instanceId);

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(instanceId).count()).isZero();
        // Withdrawn, not approved: no end event was reached.
        assertThat(endEventsReached(instanceId)).isEmpty();
    }

    @Test
    void terminatingARunThatAlreadyEndedChangesNothing() {
        String instanceId = newInstanceId();
        WorkflowStep started = start(TENANT, instanceId, "u_2");
        engine.completeTask(TENANT, instanceId, taskOf(started, "u_2").ref(), WorkflowOutcome.APPROVED);

        engine.terminate(TENANT, instanceId);

        assertThat(endEventsReached(instanceId)).containsExactly("endApproved");
    }

    @Test
    void deploymentsAndRunsAreScopedToTheTenant() {
        TenantId other = new TenantId("t_engine_other");
        start(TENANT, newInstanceId(), "u_2");
        start(other, newInstanceId(), "u_9");

        List<Deployment> deployments = repositoryService.createDeploymentQuery()
                .deploymentKey(KEY + "_v1").list();
        assertThat(deployments).extracting(Deployment::getTenantId)
                .contains(TENANT.value(), other.value());

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceTenantId(TENANT.value()).count())
                .isEqualTo(1);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceTenantId(other.value()).count())
                .isEqualTo(1);
    }

    @Test
    void anotherTenantCannotAdvanceARun() {
        String instanceId = newInstanceId();
        WorkflowStep started = start(TENANT, instanceId, "u_2");

        assertThatThrownBy(() -> engine.completeTask(new TenantId("t_intruder"), instanceId,
                taskOf(started, "u_2").ref(), WorkflowOutcome.APPROVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No workflow ref");
        assertThat(taskService.createTaskQuery().processInstanceBusinessKey(instanceId).count()).isEqualTo(1);
    }

    @Test
    void siblingApproversFinishingAtTheSameTimeEndWithBothApprovalsCounted() throws Exception {
        String instanceId = newInstanceId();
        WorkflowStep started = start(TENANT, instanceId, "u_2", "u_3");
        List<String> refs = List.of(taskOf(started, "u_2").ref(), taskOf(started, "u_3").ref());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<WorkflowStep>> results = new ArrayList<>();
            for (String ref : refs) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        return engine.completeTask(TENANT, instanceId, ref, WorkflowOutcome.APPROVED);
                    } catch (BusinessException e) {
                        // The loser of an optimistic-lock race is told to retry — never a raw engine error.
                        assertThat(e.code()).isEqualTo(BusinessErrorCode.CONFLICT);
                        return engine.completeTask(TENANT, instanceId, ref, WorkflowOutcome.APPROVED);
                    }
                }));
            }
            go.countDown();
            List<WorkflowStep> steps = new ArrayList<>();
            for (Future<WorkflowStep> result : results) {
                steps.add(result.get());
            }
            assertThat(steps).filteredOn(WorkflowStep::finished).hasSize(1)
                    .allSatisfy(step -> assertThat(step.outcome()).isEqualTo(WorkflowOutcome.APPROVED));
        } finally {
            pool.shutdownNow();
        }
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(instanceId).count()).isZero();
    }
}
