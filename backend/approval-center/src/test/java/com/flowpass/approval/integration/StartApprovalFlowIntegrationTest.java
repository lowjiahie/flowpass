package com.flowpass.approval.integration;

import com.flowpass.approval.application.command.ApproveTaskCommand;
import com.flowpass.approval.application.command.ApproveTaskHandler;
import com.flowpass.approval.application.command.RejectTaskCommand;
import com.flowpass.approval.application.command.RejectTaskHandler;
import com.flowpass.approval.application.command.StartApprovalCommand;
import com.flowpass.approval.application.command.StartApprovalHandler;
import com.flowpass.approval.application.command.WithdrawInstanceCommand;
import com.flowpass.approval.application.command.WithdrawInstanceHandler;
import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.outbox.OutboxDispatchHandler;
import com.flowpass.approval.application.query.GetTaskHandler;
import com.flowpass.approval.application.query.GetTaskQuery;
import com.flowpass.approval.application.query.ListAuditRecordsHandler;
import com.flowpass.approval.application.query.ListAuditRecordsQuery;
import com.flowpass.approval.application.query.ListTasksHandler;
import com.flowpass.approval.application.query.ListTasksQuery;
import com.flowpass.approval.contract.dto.AuditRecordDTO;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.contract.dto.ApprovalTaskDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.flowpass.approval.support.TestBpmn;
import com.flowpass.approval.support.EngineTestSupport;
import com.flowpass.approval.support.WithAllApprovalAuthorities;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end slice test for the start-approval flow against a real PostgreSQL.
 *
 * <p>Boots the whole application context with Testcontainers and drives {@link StartApprovalHandler}
 * through its real MyBatis adapters, asserting the instance is persisted, the transactional outbox row
 * is written in the same transaction, and the idempotency claim rejects a duplicate key. These are the
 * invariants that cannot be guarded by unit tests (schema, unique keys, optimistic versioning).</p>
 *
 * <p>Requires a local Docker daemon. Skips gracefully when Docker is unavailable via the JUnit
 * {@code @Testcontainers(disabledWithoutDocker = true)} extension flag.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@WithAllApprovalAuthorities
@Testcontainers(disabledWithoutDocker = true)
class StartApprovalFlowIntegrationTest {

    private static final String TENANT = "t_10086";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private StartApprovalHandler handler;

    @Autowired
    private ApproveTaskHandler approveHandler;

    @Autowired
    private RejectTaskHandler rejectHandler;

    @Autowired
    private WithdrawInstanceHandler withdrawHandler;

    @Autowired
    private ListTasksHandler listTasksHandler;

    @Autowired
    private GetTaskHandler getTaskHandler;

    @Autowired
    private ListAuditRecordsHandler listAuditRecordsHandler;

    @Autowired
    private OutboxDispatchHandler outboxDispatcher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RuntimeService runtimeService;

    @BeforeEach
    void seedPublishedDefinition() {
        // Reset the engine runtime: deleting running instances cascades their tasks (keep deployments).
        EngineTestSupport.clearRuns(runtimeService);
        jdbc.execute("""
                TRUNCATE approval_task, approval_instance, outbox_event, idempotency_record,
                         approval_workflow_ref, form_schema, process_definition, approval_template,
                         webhook_subscription, webhook_delivery, approval_action, decision_rule
                         RESTART IDENTITY CASCADE
                """);
        jdbc.update("""
                INSERT INTO approval_template (id, tenant_id, template_key, status)
                VALUES ('tpl-1', ?, 'purchase_request', 'PUBLISHED')
                """, TENANT);
        jdbc.update("""
                INSERT INTO process_definition (id, template_id, version, bpmn_xml, checksum)
                VALUES ('pd-1', 'tpl-1', 3, ?, 'chk-1')
                """, TestBpmn.purchaseRequest());
        jdbc.update("""
                INSERT INTO form_schema (id, template_id, version, json_schema)
                VALUES ('fs-1', 'tpl-1', 1, '{}')
                """);
        // Approvers now come from the approval matrix (decision context), not the start variables. Seed a
        // default rule so a started instance gets the two first-round tasks these tests drive.
        jdbc.update("""
                INSERT INTO decision_rule (id, tenant_id, template_key, priority, approvers)
                VALUES ('rule-default', ?, 'purchase_request', 100,
                        '[{"type":"USER","id":"u_2"},{"type":"USER","id":"u_3"}]'::jsonb)
                """, TENANT);
    }

    /** The real assignee of a task — task ids are random, so tests must not assume their order. */
    private ApproverRef assigneeOf(String taskId) {
        return jdbc.queryForObject(
                "SELECT assignee_type, assignee_id FROM approval_task WHERE tenant_id = ? AND id = ?",
                (rs, row) -> new ApproverRef(rs.getString("assignee_type"), rs.getString("assignee_id")),
                TENANT, taskId);
    }

    private StartApprovalCommand command(String idemKey) {
        return new StartApprovalCommand(
                new TenantId(TENANT),
                "app-default",
                "purchase_request",
                "biz-001",
                idemKey,
                new ApproverRef("USER", "u_1"),
                null,
                Map.of("amount", new BigDecimal("100.00"), "currency", "CNY"),
                null);
    }

    @Test
    void persistsInstanceAndOutboxInOneTransaction() {
        ApprovalInstanceDTO dto = handler.handle(command("idem-key-1"));

        assertThat(dto.status()).isEqualTo("RUNNING");
        assertThat(dto.definitionVersion()).isEqualTo(3);
        assertThat(dto.amount()).isEqualByComparingTo("100.00");
        assertThat(dto.currency()).isEqualTo("CNY");

        Integer instances = jdbc.queryForObject(
                "SELECT count(*) FROM approval_instance WHERE tenant_id = ?", Integer.class, TENANT);
        assertThat(instances).isEqualTo(1);

        Integer outbox = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND publish_status = 'PENDING'",
                Integer.class, TENANT);
        assertThat(outbox).isEqualTo(1);

        String eventType = jdbc.queryForObject(
                "SELECT type FROM outbox_event WHERE tenant_id = ?", String.class, TENANT);
        assertThat(eventType).isEqualTo("approval.instance.created");

        // The immutable audit ledger recorded the creation (who initiated, when).
        Integer auditStart = jdbc.queryForObject(
                "SELECT count(*) FROM approval_action WHERE tenant_id = ? AND action = 'STARTED'",
                Integer.class, TENANT);
        assertThat(auditStart).isEqualTo(1);
        String auditActor = jdbc.queryForObject(
                "SELECT actor_id FROM approval_action WHERE tenant_id = ? AND action = 'STARTED'",
                String.class, TENANT);
        assertThat(auditActor).isEqualTo("u_1");

        // The Flowable engine was started and is paused on one user task per approver (u_2, u_3).
        String processInstanceId = jdbc.queryForObject(
                "SELECT process_instance_id FROM approval_workflow_ref WHERE tenant_id = ?",
                String.class, TENANT);
        assertThat(processInstanceId).isNotBlank();
        Integer activeTasks = jdbc.queryForObject(
                "SELECT count(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = ?", Integer.class, processInstanceId);
        assertThat(activeTasks).isEqualTo(2);
    }

    @Test
    void createsFirstRoundTasksFromMatchedRule() {
        StartApprovalCommand command = new StartApprovalCommand(
                new TenantId(TENANT),
                "app-default",
                "purchase_request",
                "biz-tasks",
                "idem-key-tasks",
                new ApproverRef("USER", "u_1"),
                null,
                Map.of("amount", new BigDecimal("50.00"), "currency", "CNY"),
                null);

        handler.handle(command);

        Integer tasks = jdbc.queryForObject(
                "SELECT count(*) FROM approval_task WHERE tenant_id = ?", Integer.class, TENANT);
        assertThat(tasks).isEqualTo(2);
    }

    @Test
    void approvesTasksAndCompletesInstanceOnlyWhenAllApproved() {
        StartApprovalCommand start = new StartApprovalCommand(
                new TenantId(TENANT),
                "app-default",
                "purchase_request",
                "biz-approve",
                "idem-approve",
                new ApproverRef("USER", "u_1"),
                null,
                Map.of(),
                null);
        String instanceId = handler.handle(start).id();

        String processInstanceId = jdbc.queryForObject(
                "SELECT process_instance_id FROM approval_workflow_ref WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, instanceId);
        assertThat(processInstanceId).isNotBlank();

        List<String> taskIds = jdbc.queryForList(
                "SELECT id FROM approval_task WHERE tenant_id = ? AND instance_id = ? ORDER BY id",
                String.class, TENANT, instanceId);
        assertThat(taskIds).hasSize(2);

        ApprovalInstanceDTO afterFirst = approveHandler.handle(
                new ApproveTaskCommand(new TenantId(TENANT), taskIds.get(0), assigneeOf(taskIds.get(0)), null));
        assertThat(afterFirst.status()).isEqualTo("RUNNING");

        ApprovalInstanceDTO afterSecond = approveHandler.handle(
                new ApproveTaskCommand(new TenantId(TENANT), taskIds.get(1), assigneeOf(taskIds.get(1)), null));
        assertThat(afterSecond.status()).isEqualTo("APPROVED");

        Integer approvedEvents = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND type = 'approval.instance.approved'",
                Integer.class, TENANT);
        assertThat(approvedEvents).isEqualTo(1);

        // Each approved task left an append-only audit entry.
        Integer auditApproved = jdbc.queryForObject(
                "SELECT count(*) FROM approval_action WHERE tenant_id = ? AND action = 'APPROVED'",
                Integer.class, TENANT);
        assertThat(auditApproved).isEqualTo(2);

        // The read path exposes the immutable timeline: 1 start + 2 approved actions.
        List<AuditRecordDTO> timeline = listAuditRecordsHandler.handle(
                new ListAuditRecordsQuery(new TenantId(TENANT), instanceId, null, null, 20));
        assertThat(timeline).hasSize(3);
        assertThat(timeline).extracting(AuditRecordDTO::action)
                .containsExactlyInAnyOrder("STARTED", "APPROVED", "APPROVED");

        // The engine followed the last approval to its end node (no active user task left).
        Integer activeTasks = jdbc.queryForObject(
                "SELECT count(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = ?", Integer.class, processInstanceId);
        assertThat(activeTasks).isZero();
    }

    @Test
    void rejectsDuplicateIdempotencyKey() {
        handler.handle(command("idem-key-dup"));

        assertThatThrownBy(() -> handler.handle(command("idem-key-dup")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void rejectsUnpublishedTemplate() {
        StartApprovalCommand command = new StartApprovalCommand(
                new TenantId(TENANT),
                "app-default",
                "missing_template",
                "biz-002",
                "idem-key-2",
                new ApproverRef("USER", "u_1"),
                null,
                Map.of(),
                null);

        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    void dispatchesPendingOutboxEventsOnlyWhenSubscribed() {
        // Start an approval that emits one `approval.instance.created` outbox event.
        handler.handle(command("idem-dispatch"));

        // 1. No subscription yet: the event stays PENDING, nothing is delivered.
        OutboxDispatchHandler.DispatchSummary first = outboxDispatcher.dispatch(10);
        assertThat(first.eventsDrained()).isZero();
        assertThat(first.deliveriesEnqueued()).isZero();
        Integer pendingAfterFirst = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND publish_status = 'PENDING'",
                Integer.class, TENANT);
        assertThat(pendingAfterFirst).isEqualTo(1);

        // 2. Subscribe to the emitted event type, then the dispatcher records a delivery + flags it.
        jdbc.update("""
                INSERT INTO webhook_subscription (id, tenant_id, event_type, endpoint, enabled)
                VALUES ('sub-1', ?, 'approval.instance.created', 'https://hooks.example.com/flowpass', true)
                """, TENANT);

        OutboxDispatchHandler.DispatchSummary second = outboxDispatcher.dispatch(10);
        assertThat(second.eventsDrained()).isEqualTo(1);
        assertThat(second.deliveriesEnqueued()).isEqualTo(1);

        Integer pendingAfterSecond = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND publish_status = 'PENDING'",
                Integer.class, TENANT);
        assertThat(pendingAfterSecond).isZero();

        String eventId = jdbc.queryForObject(
                "SELECT event_id FROM outbox_event WHERE tenant_id = ?", String.class, TENANT);
        String endpoint = jdbc.queryForObject(
                "SELECT endpoint FROM webhook_delivery WHERE tenant_id = ? AND event_id = ?",
                String.class, TENANT, eventId);
        assertThat(endpoint).isEqualTo("https://hooks.example.com/flowpass");
        Integer attempt = jdbc.queryForObject(
                "SELECT attempt FROM webhook_delivery WHERE tenant_id = ? AND event_id = ?",
                Integer.class, TENANT, eventId);
        assertThat(attempt).isZero();
    }

    @Test
    void rejectsInstanceOnSingleRejectedTask() {
        // Two approval tasks so we can assert a rejection terminates the whole instance regardless of
        // the still-pending sibling task.
        StartApprovalCommand start = new StartApprovalCommand(
                new TenantId(TENANT),
                "app-default",
                "purchase_request",
                "biz-reject",
                "idem-reject",
                new ApproverRef("USER", "u_1"),
                null,
                Map.of(),
                null);
        String instanceId = handler.handle(start).id();

        String processInstanceId = jdbc.queryForObject(
                "SELECT process_instance_id FROM approval_workflow_ref WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, instanceId);
        assertThat(processInstanceId).isNotBlank();

        List<String> taskIds = jdbc.queryForList(
                "SELECT id FROM approval_task WHERE tenant_id = ? AND instance_id = ? ORDER BY id",
                String.class, TENANT, instanceId);
        assertThat(taskIds).hasSize(2);

        // Reject the first task (u_2): the instance moves terminal straight to REJECTED.
        ApprovalInstanceDTO afterReject = rejectHandler.handle(
                new RejectTaskCommand(new TenantId(TENANT), taskIds.get(0), assigneeOf(taskIds.get(0)), null));
        assertThat(afterReject.status()).isEqualTo("REJECTED");

        // The rejected task is REJECTED and the sibling the engine finished is closed with it.
        String rejectedTaskStatus = jdbc.queryForObject(
                "SELECT status FROM approval_task WHERE tenant_id = ? AND id = ?",
                String.class, TENANT, taskIds.get(0));
        assertThat(rejectedTaskStatus).isEqualTo("REJECTED");
        String siblingStatus = jdbc.queryForObject(
                "SELECT status FROM approval_task WHERE tenant_id = ? AND id = ?",
                String.class, TENANT, taskIds.get(1));
        assertThat(siblingStatus).isEqualTo("CANCELLED");

        Integer rejectedEvents = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND type = 'approval.instance.rejected'",
                Integer.class, TENANT);
        assertThat(rejectedEvents).isEqualTo(1);

        // The rejection is recorded on the immutable audit ledger.
        Integer auditRejected = jdbc.queryForObject(
                "SELECT count(*) FROM approval_action WHERE tenant_id = ? AND action = 'REJECTED'",
                Integer.class, TENANT);
        assertThat(auditRejected).isEqualTo(1);

        // The engine followed the rejection to its end node (no active user task left).
        Integer activeTasks = jdbc.queryForObject(
                "SELECT count(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = ?", Integer.class, processInstanceId);
        assertThat(activeTasks).isZero();
    }

    @Test
    void initiatorWithdrawsInstanceAndCancelsAllOpenTasks() {
        // Two open approval tasks: withdrawing the instance cancels both and terminates the engine run.
        StartApprovalCommand start = new StartApprovalCommand(
                new TenantId(TENANT),
                "app-default",
                "purchase_request",
                "biz-withdraw",
                "idem-withdraw",
                new ApproverRef("USER", "u_1"),
                null,
                Map.of(),
                null);
        String instanceId = handler.handle(start).id();

        String processInstanceId = jdbc.queryForObject(
                "SELECT process_instance_id FROM approval_workflow_ref WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, instanceId);
        assertThat(processInstanceId).isNotBlank();

        List<String> taskIds = jdbc.queryForList(
                "SELECT id FROM approval_task WHERE tenant_id = ? AND instance_id = ? ORDER BY id",
                String.class, TENANT, instanceId);
        assertThat(taskIds).hasSize(2);

        // A stranger (even an assignee) cannot withdraw: only the initiator u_1 can.
        assertThatThrownBy(() -> withdrawHandler.handle(new WithdrawInstanceCommand(
                new TenantId(TENANT), instanceId, new ApproverRef("USER", "u_2"), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.FORBIDDEN));

        // The initiator withdraws the instance: it moves terminal to WITHDRAWN.
        ApprovalInstanceDTO afterWithdraw = withdrawHandler.handle(new WithdrawInstanceCommand(
                new TenantId(TENANT), instanceId, new ApproverRef("USER", "u_1"), "changed my mind"));
        assertThat(afterWithdraw.status()).isEqualTo("WITHDRAWN");

        // Every open task was cancelled with the instance.
        for (String taskId : taskIds) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM approval_task WHERE tenant_id = ? AND id = ?",
                    String.class, TENANT, taskId);
            assertThat(status).isEqualTo("CANCELLED");
        }

        Integer withdrawnEvents = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND type = 'approval.instance.withdrawn'",
                Integer.class, TENANT);
        assertThat(withdrawnEvents).isEqualTo(1);

        // The withdrawal is recorded on the immutable audit ledger.
        Integer auditWithdrawn = jdbc.queryForObject(
                "SELECT count(*) FROM approval_action WHERE tenant_id = ? AND action = 'WITHDRAWN'",
                Integer.class, TENANT);
        assertThat(auditWithdrawn).isEqualTo(1);

        // Terminating the engine removes the process instance and its remaining active tasks.
        Integer activeTasks = jdbc.queryForObject(
                "SELECT count(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = ?", Integer.class, processInstanceId);
        assertThat(activeTasks).isZero();
    }

    @Test
    void listsAndGetsTasksForInstance() {
        // Two approval tasks so we can assert the read path pages them for the instance and fetches one.
        StartApprovalCommand start = new StartApprovalCommand(
                new TenantId(TENANT),
                "app-default",
                "purchase_request",
                "biz-read",
                "idem-read",
                new ApproverRef("USER", "u_1"),
                null,
                Map.of(),
                null);
        String instanceId = handler.handle(start).id();

        List<ApprovalTaskDTO> tasks = listTasksHandler.handle(
                new ListTasksQuery(new TenantId(TENANT), instanceId, null, null, 20));
        assertThat(tasks).hasSize(2);

        ApprovalTaskDTO first = getTaskHandler.handle(new GetTaskQuery(new TenantId(TENANT), tasks.get(0).id()));
        assertThat(first.id()).isEqualTo(tasks.get(0).id());
        assertThat(first.instanceId()).isEqualTo(instanceId);
        assertThat(first.status()).isEqualTo("PENDING");
    }

    @Test
    void eachBusinessTaskIsLinkedToTheEngineTaskThatProducedIt() {
        String instanceId = handler.handle(command("idem-link")).id();

        List<java.util.Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT assignee_id, node_key, workflow_task_ref FROM approval_task WHERE instance_id = ?",
                instanceId);
        assertThat(tasks).hasSize(2);
        for (java.util.Map<String, Object> task : tasks) {
            assertThat(task.get("node_key")).isEqualTo("approveTask");
            // The opaque reference points at a live engine task assigned to the same approver.
            String engineAssignee = jdbc.queryForObject("SELECT ASSIGNEE_ FROM ACT_RU_TASK WHERE ID_ = ?",
                    String.class, task.get("workflow_task_ref"));
            assertThat(engineAssignee).isEqualTo("USER:" + task.get("assignee_id"));
        }
    }

    @Test
    void sameBusinessKeyInTwoTenantsGivesTwoIndependentInstances() {
        String otherTenant = "t_other";
        jdbc.update("INSERT INTO approval_template (id, tenant_id, template_key, status) "
                + "VALUES ('tpl-2', ?, 'purchase_request', 'PUBLISHED')", otherTenant);
        jdbc.update("INSERT INTO process_definition (id, template_id, version, bpmn_xml, checksum) "
                + "VALUES ('pd-2', 'tpl-2', 1, ?, 'chk-2')", TestBpmn.purchaseRequest());
        jdbc.update("INSERT INTO form_schema (id, template_id, version, json_schema) VALUES ('fs-2', 'tpl-2', 1, '{}')");
        jdbc.update("INSERT INTO decision_rule (id, tenant_id, template_key, priority, approvers) "
                + "VALUES ('rule-other', ?, 'purchase_request', 100, '[{\"type\":\"USER\",\"id\":\"u_9\"}]'::jsonb)",
                otherTenant);

        String first = handler.handle(command("idem-a")).id();
        String second = handler.handle(new StartApprovalCommand(new TenantId(otherTenant), "app-default",
                "purchase_request", "biz-001", "idem-b", new ApproverRef("USER", "u_1"), null,
                Map.of("amount", new BigDecimal("100.00"), "currency", "CNY"), null)).id();

        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_instance WHERE business_key = 'biz-001'",
                Integer.class)).isEqualTo(2);
        // Each tenant got its own engine deployment and its own run.
        assertThat(jdbc.queryForList("SELECT DISTINCT TENANT_ID_ FROM ACT_RE_DEPLOYMENT WHERE NAME_ LIKE 'purchase_request_v%'",
                String.class)).contains(TENANT, otherTenant);
    }

    @Test
    void aSecondStartForTheSameBusinessKeyIsAConflictNotAServerError() {
        handler.handle(command("idem-first"));

        assertThatThrownBy(() -> handler.handle(command("idem-second")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.CONFLICT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_instance", Integer.class)).isEqualTo(1);
    }

    @Test
    void noMatchingRuleParksTheRunInsteadOfApprovingItAndOpensNoBusinessTask() {
        jdbc.update("DELETE FROM decision_rule");

        ApprovalInstanceDTO dto = handler.handle(command("idem-nobody"));

        assertThat(dto.status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_task", Integer.class)).isZero();
        // The engine holds one unassigned manual-review task — it did not run through to an approval.
        assertThat(jdbc.queryForList("""
                SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK t JOIN approval_workflow_ref r
                  ON t.PROC_INST_ID_ = r.process_instance_id WHERE r.instance_id = ?
                """, String.class, dto.id())).containsExactly("manualReview");
    }
}
