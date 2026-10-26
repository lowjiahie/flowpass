package com.flowpass.approval.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowpass.approval.support.EngineTestSupport;
import com.flowpass.approval.support.TestBpmn;
import com.jayway.jsonpath.JsonPath;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transfer, add-sign and return through the HTTP API on a real engine: who may do them, who may be brought in,
 * how the round and the business view follow, and that a refusal changes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class TaskActionsIntegrationTest {

    private static final String TENANT = "t_act";
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
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private RuntimeService runtimeService;

    private int sequence;

    @BeforeEach
    void seed() throws Exception {
        EngineTestSupport.clearRuns(runtimeService);
        jdbc.execute("""
                TRUNCATE approval_task, approval_instance, outbox_event, idempotency_record,
                         approval_workflow_ref, form_schema, process_definition, approval_template,
                         webhook_subscription, webhook_delivery, approval_action, decision_rule,
                         directory_user, directory_department, directory_role_member
                         RESTART IDENTITY CASCADE
                """);
        TestBpmn.seedPublished(jdbc, TENANT, KEY, 1);
        jdbc.update("INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES (?, 'finance', 'u_fin1')", TENANT);
        jdbc.update("INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES (?, 'finance', 'u_fin2')", TENANT);

        // >= 5000: u_a; then u_b and u_c together.   >= 1000: u_a and u_b; then finance; then u_d.   else u_a.
        rule(5, "amount >= 5000", List.of(approver("USER", "u_a", 1), approver("USER", "u_b", 2), approver("USER", "u_c", 2)));
        rule(10, "amount >= 1000", List.of(approver("USER", "u_a", 1), approver("USER", "u_b", 1),
                approver("ROLE", "finance", 2), approver("USER", "u_d", 3)));
        rule(100, null, List.of(approver("USER", "u_a", 1)));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static Map<String, Object> approver(String type, String id, int round) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("round", round);
        return m;
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String actor) {
        return request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", actor);
    }

    private void rule(int priority, String expression, List<Map<String, Object>> approvers) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateKey", KEY);
        body.put("priority", priority);
        if (expression != null) {
            body.put("expression", expression);
        }
        body.put("approvers", approvers);
        mvc.perform(as(post("/v1/admin/decision-rules"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isCreated());
    }

    private String start(int amount) throws Exception {
        String bizKey = "biz-" + (++sequence);
        String body = mvc.perform(as(post("/v1/approval-instances"), "u_1").header("Idempotency-Key", "idem-" + bizKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"externalBusinessKey\":\"" + bizKey
                                + "\",\"variables\":{\"amount\":" + amount + ",\"currency\":\"MYR\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private List<String> open(String instanceId) {
        return jdbc.queryForList("""
                SELECT assignee_type || ':' || assignee_id FROM approval_task
                WHERE instance_id = ? AND status IN ('PENDING', 'CLAIMED') ORDER BY assignee_id
                """, String.class, instanceId);
    }

    private String openTask(String instanceId, String assignee) {
        String[] parts = assignee.split(":");
        return jdbc.queryForObject("""
                SELECT id FROM approval_task WHERE instance_id = ? AND assignee_type = ? AND assignee_id = ?
                  AND status IN ('PENDING', 'CLAIMED')
                """, String.class, instanceId, parts[0], parts[1]);
    }

    private List<String> engineAssignees(String instanceId) {
        return jdbc.queryForList("""
                SELECT t.ASSIGNEE_ FROM ACT_RU_TASK t JOIN approval_workflow_ref r ON t.PROC_INST_ID_ = r.process_instance_id
                WHERE r.instance_id = ? ORDER BY t.ASSIGNEE_
                """, String.class, instanceId);
    }

    private MockHttpServletRequestBuilder action(String taskId, String verb, String actor, String body) {
        return as(post("/v1/tasks/" + taskId + "/" + verb), actor).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String approve(String instanceId, String assignee, String actor) throws Exception {
        String body = mvc.perform(action(openTask(instanceId, assignee), "approve", actor, "{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.status");
    }

    private String instanceStatus(String instanceId) {
        return jdbc.queryForObject("SELECT status FROM approval_instance WHERE id = ?", String.class, instanceId);
    }

    // ---- transfer ------------------------------------------------------------------------------------

    @Test
    void aTransferMovesTheTaskToTheNewPersonInBothViews() throws Exception {
        String id = start(2000);
        String task = openTask(id, "USER:u_a");

        mvc.perform(action(task, "transfer", "u_a", "{\"to\":\"u_new\",\"comment\":\"on leave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value("u_new"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(open(id)).containsExactly("USER:u_b", "USER:u_new");
        assertThat(engineAssignees(id)).containsExactly("USER:u_b", "USER:u_new");
        assertThat(jdbc.queryForObject("SELECT comment FROM approval_action WHERE action = 'TRANSFERRED'", String.class))
                .isEqualTo("USER:u_a -> u_new: on leave");
        // The old assignee has lost the task, the new one sees it and can act.
        mvc.perform(action(task, "approve", "u_a", "{}")).andExpect(status().isForbidden());
        mvc.perform(as(get("/v1/tasks?mine=true&status=PENDING"), "u_new"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(task));
        mvc.perform(as(get("/v1/tasks?mine=true&status=PENDING"), "u_a")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(action(task, "approve", "u_new", "{}")).andExpect(status().isOk());
    }

    @Test
    void aTransferredTaskStillCountsAsItsSlotInTheRound() throws Exception {
        String id = start(2000);
        mvc.perform(action(openTask(id, "USER:u_a"), "transfer", "u_a", "{\"to\":\"u_new\"}")).andExpect(status().isOk());

        assertThat(approve(id, "USER:u_new", "u_new")).isEqualTo("RUNNING");
        assertThat(open(id)).as("round 1 still waits for u_b").containsExactly("USER:u_b");
        assertThat(approve(id, "USER:u_b", "u_b")).isEqualTo("RUNNING");
        assertThat(open(id)).as("then the finance round").containsExactly("ROLE:finance");
    }

    @Test
    void aRoleMemberCanHandTheRoleTaskToOnePerson() throws Exception {
        String id = start(2000);
        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");

        mvc.perform(action(openTask(id, "ROLE:finance"), "transfer", "u_fin1", "{\"to\":\"u_fin2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeType").value("USER"))
                .andExpect(jsonPath("$.assigneeId").value("u_fin2"));

        assertThat(open(id)).containsExactly("USER:u_fin2");
        mvc.perform(action(openTask(id, "USER:u_fin2"), "approve", "u_fin1", "{}")).andExpect(status().isForbidden());
    }

    @Test
    void onlyTheAssigneeMayTransferAndNothingChangesWhenRefused() throws Exception {
        String id = start(2000);
        String task = openTask(id, "USER:u_a");

        mvc.perform(action(task, "transfer", "u_b", "{\"to\":\"u_new\"}")).andExpect(status().isForbidden());
        mvc.perform(action(task, "transfer", "u_1", "{\"to\":\"u_new\"}")).andExpect(status().isForbidden());

        assertThat(open(id)).containsExactly("USER:u_a", "USER:u_b");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_action WHERE action = 'TRANSFERRED'", Integer.class)).isZero();
    }

    @Test
    void theInitiatorSomeoneAlreadyInvolvedAndABlankTargetAreRefused() throws Exception {
        String id = start(2000);
        String task = openTask(id, "USER:u_a");

        mvc.perform(action(task, "transfer", "u_a", "{\"to\":\"u_1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.validation_failed"))
                .andExpect(jsonPath("$.detail").value(containsString("own request")));
        mvc.perform(action(task, "transfer", "u_a", "{\"to\":\"u_b\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("already involved")));
        mvc.perform(action(task, "transfer", "u_a", "{\"to\":\"u_a\"}")).andExpect(status().isBadRequest());
        mvc.perform(action(task, "transfer", "u_a", "{\"to\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(action(task, "transfer", "u_a", "{}")).andExpect(status().isBadRequest());

        assertThat(open(id)).containsExactly("USER:u_a", "USER:u_b");
        assertThat(engineAssignees(id)).containsExactly("USER:u_a", "USER:u_b");
    }

    @Test
    void aFinishedTaskCannotBeTransferred() throws Exception {
        String id = start(2000);
        String task = openTask(id, "USER:u_a");
        approve(id, "USER:u_a", "u_a");

        mvc.perform(action(task, "transfer", "u_a", "{\"to\":\"u_new\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.illegal_state"));
    }

    @Test
    void theInitiatorCannotUseARoleTheyHoldToHandOverTheirOwnRequest() throws Exception {
        jdbc.update("INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES (?, 'finance', 'u_1')", TENANT);
        String id = start(2000);
        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");

        mvc.perform(action(openTask(id, "ROLE:finance"), "transfer", "u_1", "{\"to\":\"u_fin2\"}"))
                .andExpect(status().isForbidden());
        assertThat(open(id)).containsExactly("ROLE:finance");
    }

    @Test
    void concurrentTransfersOfOneTaskHaveASingleWinner() throws Exception {
        String id = start(2000);
        String task = openTask(id, "USER:u_a");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        int ok = 0;
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String target = "u_t" + i;
                results.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(action(task, "transfer", "u_a", "{\"to\":\"" + target + "\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                int code = result.get();
                if (code == 200) {
                    ok++;
                } else {
                    assertThat(code).as("losers are refused, never a server error").isIn(400, 403, 409);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ok).isEqualTo(1);
        assertThat(open(id)).hasSize(2).contains("USER:u_b");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_action WHERE action = 'TRANSFERRED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void transferNeedsTheApproveAuthority() throws Exception {
        String id = start(2000);

        mvc.perform(action(openTask(id, "USER:u_a"), "transfer", "u_a", "{\"to\":\"u_new\"}")
                        .header("X-Actor-Authorities", "approval.request.read"))
                .andExpect(status().isForbidden());
    }

    // ---- add-sign ------------------------------------------------------------------------------------

    @Test
    void anAddedApproverMustAlsoApproveBeforeTheRoundCompletes() throws Exception {
        String id = start(2000);

        mvc.perform(action(openTask(id, "USER:u_a"), "add-approver", "u_a", "{\"userId\":\"u_extra\",\"comment\":\"need legal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value("u_extra"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(open(id)).containsExactly("USER:u_a", "USER:u_b", "USER:u_extra");
        assertThat(engineAssignees(id)).containsExactly("USER:u_a", "USER:u_b", "USER:u_extra");
        assertThat(jdbc.queryForObject("SELECT comment FROM approval_action WHERE action = 'APPROVER_ADDED'", String.class))
                .isEqualTo("added u_extra: need legal");

        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");
        assertThat(open(id)).as("the finance round waits for the added approver").containsExactly("USER:u_extra");
        assertThat(approve(id, "USER:u_extra", "u_extra")).isEqualTo("RUNNING");
        assertThat(open(id)).containsExactly("ROLE:finance");
    }

    @Test
    void theAddedApproverCanRejectWhichEndsTheRequest() throws Exception {
        String id = start(2000);
        mvc.perform(action(openTask(id, "USER:u_a"), "add-approver", "u_a", "{\"userId\":\"u_extra\"}")).andExpect(status().isOk());

        mvc.perform(action(openTask(id, "USER:u_extra"), "reject", "u_extra", "{\"comment\":\"no\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(instanceStatus(id)).isEqualTo("REJECTED");
        assertThat(open(id)).isEmpty();
    }

    @Test
    void severalApproversCanBeAddedByDifferentPeople() throws Exception {
        String id = start(2000);
        mvc.perform(action(openTask(id, "USER:u_a"), "add-approver", "u_a", "{\"userId\":\"u_x\"}")).andExpect(status().isOk());
        mvc.perform(action(openTask(id, "USER:u_b"), "add-approver", "u_b", "{\"userId\":\"u_y\"}")).andExpect(status().isOk());

        assertThat(open(id)).containsExactly("USER:u_a", "USER:u_b", "USER:u_x", "USER:u_y");
    }

    @Test
    void theInitiatorAndPeopleAlreadyInvolvedCannotBeAdded() throws Exception {
        String id = start(2000);
        String task = openTask(id, "USER:u_a");

        mvc.perform(action(task, "add-approver", "u_a", "{\"userId\":\"u_1\"}")).andExpect(status().isBadRequest());
        mvc.perform(action(task, "add-approver", "u_a", "{\"userId\":\"u_b\"}")).andExpect(status().isBadRequest());
        mvc.perform(action(task, "add-approver", "u_a", "{\"userId\":\"u_a\"}")).andExpect(status().isBadRequest());
        mvc.perform(action(task, "add-approver", "u_a", "{}")).andExpect(status().isBadRequest());

        assertThat(open(id)).containsExactly("USER:u_a", "USER:u_b");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_action WHERE action = 'APPROVER_ADDED'", Integer.class)).isZero();
    }

    @Test
    void onlyAnApproverOfTheRoundCanAddAnother() throws Exception {
        String id = start(2000);

        mvc.perform(action(openTask(id, "USER:u_a"), "add-approver", "u_fin1", "{\"userId\":\"u_x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(action(openTask(id, "USER:u_a"), "add-approver", "u_1", "{\"userId\":\"u_x\"}"))
                .andExpect(status().isForbidden());
        assertThat(open(id)).containsExactly("USER:u_a", "USER:u_b");
    }

    @Test
    void aRoleTaskCanAlsoTakeAnAddedApprover() throws Exception {
        String id = start(2000);
        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");

        mvc.perform(action(openTask(id, "ROLE:finance"), "add-approver", "u_fin1", "{\"userId\":\"u_audit\"}"))
                .andExpect(status().isOk());

        assertThat(open(id)).containsExactly("ROLE:finance", "USER:u_audit");
        approve(id, "ROLE:finance", "u_fin2");
        assertThat(open(id)).as("round 3 waits for the added approver as well").containsExactly("USER:u_audit");
        assertThat(approve(id, "USER:u_audit", "u_audit")).isEqualTo("RUNNING");
        assertThat(open(id)).containsExactly("USER:u_d");
    }

    // ---- return --------------------------------------------------------------------------------------

    @Test
    void returningReopensThePreviousRoundAndTheChainThenRunsToTheEnd() throws Exception {
        String id = start(2000);
        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");
        String financeTask = openTask(id, "ROLE:finance");

        mvc.perform(action(financeTask, "return", "u_fin1", "{\"comment\":\"quote is missing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        // The finance task is closed as RETURNED; round 1 is open again for the same two people.
        assertThat(jdbc.queryForObject("SELECT status FROM approval_task WHERE id = ?", String.class, financeTask))
                .isEqualTo("RETURNED");
        assertThat(open(id)).containsExactly("USER:u_a", "USER:u_b");
        assertThat(engineAssignees(id)).containsExactly("USER:u_a", "USER:u_b");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_task WHERE instance_id = ? AND assignee_id = 'u_a'",
                Integer.class, id)).as("the earlier approval stays in the history").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT comment FROM approval_action WHERE action = 'RETURNED'", String.class))
                .isEqualTo("quote is missing");
        assertThat(instanceStatus(id)).isEqualTo("RUNNING");

        // Round 1 must be redone, then finance and round 3 follow, and the request can still be approved.
        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");
        assertThat(open(id)).containsExactly("ROLE:finance");
        approve(id, "ROLE:finance", "u_fin2");
        assertThat(open(id)).containsExactly("USER:u_d");
        assertThat(approve(id, "USER:u_d", "u_d")).isEqualTo("APPROVED");
    }

    @Test
    void returningDiscardsTheApprovalsAlreadyGivenInTheRoundAndCancelsItsOtherTasks() throws Exception {
        String id = start(6000);
        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");
        String cTask = openTask(id, "USER:u_c");

        mvc.perform(action(cTask, "return", "u_c", "{}")).andExpect(status().isOk());

        assertThat(open(id)).as("only round 1 is open again").containsExactly("USER:u_a");
        approve(id, "USER:u_a", "u_a");
        assertThat(open(id)).as("u_b is asked again, the earlier approval does not count").containsExactly("USER:u_b", "USER:u_c");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_task WHERE instance_id = ? AND assignee_id = 'u_b'",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void theOtherOpenTasksOfTheDiscardedRoundAreClosed() throws Exception {
        String id = start(6000);
        approve(id, "USER:u_a", "u_a");
        String bTask = openTask(id, "USER:u_b");
        String cTask = openTask(id, "USER:u_c");

        mvc.perform(action(bTask, "return", "u_b", "{}")).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM approval_task WHERE id = ?", String.class, bTask)).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject("SELECT status FROM approval_task WHERE id = ?", String.class, cTask)).isEqualTo("CANCELLED");
    }

    @Test
    void theFirstRoundHasNothingToReturnToAndNothingChanges() throws Exception {
        String id = start(2000);

        mvc.perform(action(openTask(id, "USER:u_a"), "return", "u_a", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.illegal_state"))
                .andExpect(jsonPath("$.detail").value(containsString("no earlier round")));

        assertThat(open(id)).containsExactly("USER:u_a", "USER:u_b");
        assertThat(engineAssignees(id)).containsExactly("USER:u_a", "USER:u_b");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_action WHERE action = 'RETURNED'", Integer.class)).isZero();
    }

    @Test
    void onlyAnApproverOfTheTaskCanReturnIt() throws Exception {
        String id = start(2000);
        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");
        String financeTask = openTask(id, "ROLE:finance");

        mvc.perform(action(financeTask, "return", "u_a", "{}")).andExpect(status().isForbidden());
        mvc.perform(action(financeTask, "return", "u_1", "{}")).andExpect(status().isForbidden());
        assertThat(open(id)).containsExactly("ROLE:finance");
    }

    @Test
    void aReturnedRequestCanBeRejectedWithdrawnOrReturnedAgain() throws Exception {
        String id = start(2000);
        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");
        mvc.perform(action(openTask(id, "ROLE:finance"), "return", "u_fin1", "{}")).andExpect(status().isOk());

        // Withdraw while it is back in round 1.
        mvc.perform(as(post("/v1/approval-instances/" + id + "/withdraw"), "u_1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        assertThat(open(id)).isEmpty();
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceTenantId(TENANT).count()).isZero();
    }

    @Test
    void returnNeedsTheApproveAuthority() throws Exception {
        String id = start(2000);
        approve(id, "USER:u_a", "u_a");
        approve(id, "USER:u_b", "u_b");

        mvc.perform(action(openTask(id, "ROLE:finance"), "return", "u_fin1", "{}")
                        .header("X-Actor-Authorities", "approval.request.read"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allThreeActionsAreInTheAuditTrailOfTheRequest() throws Exception {
        String id = start(2000);
        mvc.perform(action(openTask(id, "USER:u_a"), "transfer", "u_a", "{\"to\":\"u_new\"}")).andExpect(status().isOk());
        mvc.perform(action(openTask(id, "USER:u_b"), "add-approver", "u_b", "{\"userId\":\"u_x\"}")).andExpect(status().isOk());
        approve(id, "USER:u_new", "u_new");
        approve(id, "USER:u_b", "u_b");
        approve(id, "USER:u_x", "u_x");
        mvc.perform(action(openTask(id, "ROLE:finance"), "return", "u_fin1", "{}")).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT action FROM approval_action WHERE instance_id = ? ORDER BY created_at, id",
                String.class, id))
                .contains("TRANSFERRED", "APPROVER_ADDED", "RETURNED");
        mvc.perform(as(get("/v1/admin/audit-records?instanceId=" + id + "&limit=50"), "u_admin"))
                .andExpect(status().isOk());
    }
}
