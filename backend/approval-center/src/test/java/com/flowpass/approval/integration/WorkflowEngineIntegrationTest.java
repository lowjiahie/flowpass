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
        return engine.start(tenant, instanceId, KEY, 1, Map.of("amount", 100),
                approverIds.length == 0 ? List.of() : List.of(approvers(approverIds)));
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

    // ---- approval rounds -------------------------------------------------------------------------------

    private WorkflowStep startRounds(String instanceId, List<List<String>> rounds) {
        return engine.start(TENANT, instanceId, KEY, 1, Map.of("amount", 100),
                rounds.stream().map(r -> approvers(r.toArray(new String[0]))).toList());
    }

    private static List<String> assignees(WorkflowStep step) {
        return step.newTasks().stream().map(WorkflowTask::assigneeId).toList();
    }

    @Test
    void roundsOpenOneAfterTheOtherAndTheRunEndsApprovedAfterTheLast() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2"), List.of("u_3", "u_4"), List.of("u_5")));

        // Only round 1 is open; the later rounds do not exist yet.
        assertThat(assignees(round1)).containsExactly("u_2");
        assertThat(round1.newTasks()).extracting(WorkflowTask::nodeKey).containsOnly("approveTask");

        WorkflowStep round2 = engine.completeTask(TENANT, instanceId, taskOf(round1, "u_2").ref(), WorkflowOutcome.APPROVED);
        assertThat(round2.finished()).isFalse();
        assertThat(assignees(round2)).containsExactlyInAnyOrder("u_3", "u_4");
        assertThat(round2.newTasks()).extracting(WorkflowTask::nodeKey).containsOnly("approveTask2");

        // Round 2 needs both of its approvers before round 3 opens.
        WorkflowStep half = engine.completeTask(TENANT, instanceId, taskOf(round2, "u_3").ref(), WorkflowOutcome.APPROVED);
        assertThat(half.newTasks()).isEmpty();
        assertThat(half.finished()).isFalse();
        WorkflowStep round3 = engine.completeTask(TENANT, instanceId, taskOf(round2, "u_4").ref(), WorkflowOutcome.APPROVED);
        assertThat(assignees(round3)).containsExactly("u_5");
        assertThat(round3.newTasks()).extracting(WorkflowTask::nodeKey).containsOnly("approveTask3");

        WorkflowStep done = engine.completeTask(TENANT, instanceId, taskOf(round3, "u_5").ref(), WorkflowOutcome.APPROVED);
        assertThat(done.finished()).isTrue();
        assertThat(done.outcome()).isEqualTo(WorkflowOutcome.APPROVED);
        assertThat(endEventsReached(instanceId)).containsExactly("endApproved");
    }

    @Test
    void aRejectionInALaterRoundEndsTheRunAndNeverOpensTheNextRound() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2"), List.of("u_3", "u_4"), List.of("u_5")));
        WorkflowStep round2 = engine.completeTask(TENANT, instanceId, taskOf(round1, "u_2").ref(), WorkflowOutcome.APPROVED);
        engine.completeTask(TENANT, instanceId, taskOf(round2, "u_3").ref(), WorkflowOutcome.APPROVED);

        WorkflowStep rejected = engine.completeTask(TENANT, instanceId, taskOf(round2, "u_4").ref(), WorkflowOutcome.REJECTED);

        assertThat(rejected.finished()).isTrue();
        assertThat(rejected.outcome()).isEqualTo(WorkflowOutcome.REJECTED);
        assertThat(rejected.newTasks()).isEmpty();
        assertThat(taskService.createTaskQuery().processInstanceBusinessKey(instanceId).count()).isZero();
        assertThat(endEventsReached(instanceId)).containsExactly("endRejected");
    }

    @Test
    void aRejectionInTheFirstRoundNeverOpensTheLaterOnes() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2", "u_3"), List.of("u_4")));

        WorkflowStep rejected = engine.completeTask(TENANT, instanceId, taskOf(round1, "u_2").ref(), WorkflowOutcome.REJECTED);

        assertThat(rejected.outcome()).isEqualTo(WorkflowOutcome.REJECTED);
        assertThat(rejected.newTasks()).isEmpty();
    }

    @Test
    void aChainOfOneRoundEndsAfterThatRound() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2")));

        assertThat(engine.completeTask(TENANT, instanceId, taskOf(round1, "u_2").ref(), WorkflowOutcome.APPROVED).outcome())
                .isEqualTo(WorkflowOutcome.APPROVED);
    }

    @Test
    void emptyRoundsAreSkippedSoTheChainHasNoHoles() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2"), List.of(), List.of("u_5")));

        WorkflowStep next = engine.completeTask(TENANT, instanceId, taskOf(round1, "u_2").ref(), WorkflowOutcome.APPROVED);

        assertThat(assignees(next)).as("u_5 is not lost behind the empty round").containsExactly("u_5");
    }

    @Test
    void aChainWithMoreRoundsThanTheEngineSupportsIsRefused() {
        List<List<String>> tooMany = java.util.stream.IntStream.rangeClosed(1, WorkflowEnginePort.MAX_ROUNDS + 1)
                .mapToObj(i -> List.of("u_" + i)).toList();

        assertThatThrownBy(() -> startRounds(newInstanceId(), tooMany))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 5");
    }

    @Test
    void whenNoRoundHasAnyoneTheRunParksForManualReview() {
        WorkflowStep step = startRounds(newInstanceId(), List.of(List.of(), List.of()));

        assertThat(step.finished()).isFalse();
        assertThat(step.newTasks()).singleElement().satisfies(task -> assertThat(task.nodeKey()).isEqualTo("manualReview"));
    }

    // ---- transfer, add-sign, return --------------------------------------------------------------------

    private static CandidateApprover user(String id) {
        return new CandidateApprover("USER", id);
    }

    private List<String> openAssignees(String instanceId) {
        return taskService.createTaskQuery().processInstanceBusinessKey(instanceId).list().stream()
                .map(org.flowable.task.api.Task::getAssignee).sorted().toList();
    }

    @Test
    void anOpenTaskCanBeHandedToAnotherUserAndTheRoundStillNeedsEveryone() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2", "u_3"), List.of("u_5")));

        engine.reassign(TENANT, instanceId, taskOf(round1, "u_2").ref(), user("u_9"));

        assertThat(openAssignees(instanceId)).containsExactly("USER:u_3", "USER:u_9");
        engine.completeTask(TENANT, instanceId, taskOf(round1, "u_3").ref(), WorkflowOutcome.APPROVED);
        WorkflowStep next = engine.completeTask(TENANT, instanceId, taskOf(round1, "u_2").ref(), WorkflowOutcome.APPROVED);
        assertThat(assignees(next)).as("the transferred task counted as the first slot").containsExactly("u_5");
    }

    @Test
    void reassigningATaskThatIsNotActiveIsRefused() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2")));
        String ref = taskOf(round1, "u_2").ref();
        engine.completeTask(TENANT, instanceId, ref, WorkflowOutcome.APPROVED);

        assertThatThrownBy(() -> engine.reassign(TENANT, instanceId, ref, user("u_9")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anApproverAddedToARoundMustAlsoApproveBeforeTheRoundCompletes() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2"), List.of("u_5")));

        WorkflowStep added = engine.addApprover(TENANT, instanceId, taskOf(round1, "u_2").ref(), user("u_9"));

        assertThat(assignees(added)).containsExactly("u_9");
        assertThat(added.newTasks()).extracting(WorkflowTask::nodeKey).containsOnly("approveTask");
        assertThat(openAssignees(instanceId)).containsExactly("USER:u_2", "USER:u_9");

        WorkflowStep afterFirst = engine.completeTask(TENANT, instanceId, taskOf(round1, "u_2").ref(), WorkflowOutcome.APPROVED);
        assertThat(afterFirst.newTasks()).as("round 2 waits for the added approver").isEmpty();
        assertThat(afterFirst.finished()).isFalse();

        WorkflowStep afterSecond = engine.completeTask(TENANT, instanceId, added.newTasks().get(0).ref(), WorkflowOutcome.APPROVED);
        assertThat(assignees(afterSecond)).containsExactly("u_5");
    }

    @Test
    void anAddedApproverCanRejectAndThatEndsTheRun() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2")));
        WorkflowStep added = engine.addApprover(TENANT, instanceId, taskOf(round1, "u_2").ref(), user("u_9"));

        WorkflowStep rejected = engine.completeTask(TENANT, instanceId, added.newTasks().get(0).ref(), WorkflowOutcome.REJECTED);

        assertThat(rejected.outcome()).isEqualTo(WorkflowOutcome.REJECTED);
        assertThat(taskService.createTaskQuery().processInstanceBusinessKey(instanceId).count()).isZero();
    }

    @Test
    void severalApproversCanBeAddedEvenAfterSomeoneAlreadyApproved() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2", "u_3")));
        engine.completeTask(TENANT, instanceId, taskOf(round1, "u_2").ref(), WorkflowOutcome.APPROVED);

        WorkflowStep a = engine.addApprover(TENANT, instanceId, taskOf(round1, "u_3").ref(), user("u_8"));
        WorkflowStep b = engine.addApprover(TENANT, instanceId, taskOf(round1, "u_3").ref(), user("u_9"));

        assertThat(openAssignees(instanceId)).containsExactly("USER:u_3", "USER:u_8", "USER:u_9");
        engine.completeTask(TENANT, instanceId, taskOf(round1, "u_3").ref(), WorkflowOutcome.APPROVED);
        engine.completeTask(TENANT, instanceId, a.newTasks().get(0).ref(), WorkflowOutcome.APPROVED);
        assertThat(engine.completeTask(TENANT, instanceId, b.newTasks().get(0).ref(), WorkflowOutcome.APPROVED).outcome())
                .isEqualTo(WorkflowOutcome.APPROVED);
    }

    @Test
    void aManualReviewTaskCannotTakeMoreApprovers() {
        String instanceId = newInstanceId();
        WorkflowStep parked = startRounds(instanceId, List.of());
        String ref = parked.newTasks().get(0).ref();

        assertThatThrownBy(() -> engine.addApprover(TENANT, instanceId, ref, user("u_9")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot take more approvers");
        assertThat(taskService.createTaskQuery().processInstanceBusinessKey(instanceId).count()).isEqualTo(1);
    }

    @Test
    void aTaskOfALaterRoundCanSendTheRunBackToThePreviousRound() {
        String instanceId = newInstanceId();
        WorkflowStep round1 = startRounds(instanceId, List.of(List.of("u_2", "u_3"), List.of("u_5")));
        engine.completeTask(TENANT, instanceId, taskOf(round1, "u_2").ref(), WorkflowOutcome.APPROVED);
        WorkflowStep round2 = engine.completeTask(TENANT, instanceId, taskOf(round1, "u_3").ref(), WorkflowOutcome.APPROVED);

        WorkflowStep back = engine.returnToPreviousRound(TENANT, instanceId, taskOf(round2, "u_5").ref());

        assertThat(back.finished()).isFalse();
        assertThat(assignees(back)).containsExactlyInAnyOrder("u_2", "u_3");
        assertThat(back.newTasks()).extracting(WorkflowTask::nodeKey).containsOnly("approveTask");
        assertThat(openAssignees(instanceId)).containsExactly("USER:u_2", "USER:u_3");

        engine.completeTask(TENANT, instanceId, taskOf(back, "u_2").ref(), WorkflowOutcome.APPROVED);
        WorkflowStep reopened = engine.completeTask(TENANT, instanceId, taskOf(back, "u_3").ref(), WorkflowOutcome.APPROVED);
        assertThat(assignees(reopened)).containsExactly("u_5");
        assertThat(engine.completeTask(TENANT, instanceId, reopened.newTasks().get(0).ref(), WorkflowOutcome.APPROVED).outcome())
                .isEqualTo(WorkflowOutcome.APPROVED);
    }

    @Test
    void returningFromAThirdRoundGoesToTheSecondNotTheFirst() {
        String instanceId = newInstanceId();
        WorkflowStep r1 = startRounds(instanceId, List.of(List.of("u_2"), List.of("u_3"), List.of("u_4")));
        WorkflowStep r2 = engine.completeTask(TENANT, instanceId, taskOf(r1, "u_2").ref(), WorkflowOutcome.APPROVED);
        WorkflowStep r3 = engine.completeTask(TENANT, instanceId, taskOf(r2, "u_3").ref(), WorkflowOutcome.APPROVED);

        WorkflowStep back = engine.returnToPreviousRound(TENANT, instanceId, taskOf(r3, "u_4").ref());

        assertThat(assignees(back)).containsExactly("u_3");
        assertThat(back.newTasks()).extracting(WorkflowTask::nodeKey).containsOnly("approveTask2");
    }

    @Test
    void returningDiscardsTheProgressOfTheRoundBeingReturnedFrom() {
        String instanceId = newInstanceId();
        WorkflowStep r1 = startRounds(instanceId, List.of(List.of("u_2"), List.of("u_5", "u_6")));
        WorkflowStep r2 = engine.completeTask(TENANT, instanceId, taskOf(r1, "u_2").ref(), WorkflowOutcome.APPROVED);
        engine.completeTask(TENANT, instanceId, taskOf(r2, "u_5").ref(), WorkflowOutcome.APPROVED);

        WorkflowStep back = engine.returnToPreviousRound(TENANT, instanceId, taskOf(r2, "u_6").ref());

        assertThat(assignees(back)).containsExactly("u_2");
        WorkflowStep again = engine.completeTask(TENANT, instanceId, taskOf(back, "u_2").ref(), WorkflowOutcome.APPROVED);
        assertThat(assignees(again)).as("both round-2 approvers are asked again").containsExactlyInAnyOrder("u_5", "u_6");
    }

    @Test
    void aReturnCanBeFollowedByARejectionInTheReopenedRound() {
        String instanceId = newInstanceId();
        WorkflowStep r1 = startRounds(instanceId, List.of(List.of("u_2"), List.of("u_5")));
        WorkflowStep r2 = engine.completeTask(TENANT, instanceId, taskOf(r1, "u_2").ref(), WorkflowOutcome.APPROVED);
        WorkflowStep back = engine.returnToPreviousRound(TENANT, instanceId, taskOf(r2, "u_5").ref());

        WorkflowStep rejected = engine.completeTask(TENANT, instanceId, taskOf(back, "u_2").ref(), WorkflowOutcome.REJECTED);

        assertThat(rejected.outcome()).isEqualTo(WorkflowOutcome.REJECTED);
    }

    @Test
    void theFirstRoundHasNothingToReturnToAndNothingChanges() {
        String instanceId = newInstanceId();
        WorkflowStep r1 = startRounds(instanceId, List.of(List.of("u_2", "u_3"), List.of("u_5")));

        assertThatThrownBy(() -> engine.returnToPreviousRound(TENANT, instanceId, taskOf(r1, "u_2").ref()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no earlier round");

        assertThat(openAssignees(instanceId)).containsExactly("USER:u_2", "USER:u_3");
    }

    @Test
    void aDefinitionThatDoesNotRouteReturnsIsRefusedAndTheAttemptIsUndone() {
        // Same flow, but round 2 has no working "returned" branch.
        String key = "no_return";
        String xml = TestBpmn.forKey(key).replaceAll(
                "(id=\"flow_return_to_round1\"[^>]*>\\s*<conditionExpression[^>]*>)\\$\\{returned}", "$1\\${false}");
        assertThat(xml).isNotEqualTo(TestBpmn.forKey(key));
        jdbc.update("INSERT INTO approval_template (id, tenant_id, template_key, status, latest_version) VALUES ('tpl-nr', ?, ?, 'PUBLISHED', 1)",
                TENANT.value(), key);
        jdbc.update("INSERT INTO process_definition (id, template_id, version, bpmn_xml, checksum) VALUES ('pd-nr', 'tpl-nr', 1, ?, 'chk')", xml);

        String instanceId = newInstanceId();
        WorkflowStep r1 = engine.start(TENANT, instanceId, key, 1, Map.of(),
                List.of(approvers("u_2"), approvers("u_5", "u_6")));
        WorkflowStep r2 = engine.completeTask(TENANT, instanceId, taskOf(r1, "u_2").ref(), WorkflowOutcome.APPROVED);

        assertThatThrownBy(() -> engine.returnToPreviousRound(TENANT, instanceId, taskOf(r2, "u_5").ref()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not support returning");

        // Nothing happened: round 2 is intact, both tasks still open, the run still going.
        assertThat(openAssignees(instanceId)).containsExactly("USER:u_5", "USER:u_6");
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(instanceId).count()).isEqualTo(1);
    }
}
