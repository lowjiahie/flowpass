package com.flowpass.approval.integration;

import com.jayway.jsonpath.JsonPath;
import com.flowpass.approval.support.TestBpmn;
import com.flowpass.approval.support.EngineTestSupport;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level proof of the identity and authorization rules, through the real security filter chain
 * (the {@code dev} header identity) and the real {@code @PreAuthorize} handlers against PostgreSQL.
 *
 * <p>Covers: no identity → 401, wrong actor → 403, missing authority → 403, cross-tenant → 404, the
 * request body can no longer name the actor, and a double approval races to exactly one winner.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class SecurityDevHttpIntegrationTest {

    private static final String TENANT = "t_sec";
    private static final String OTHER_TENANT = "t_other";

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
    private RuntimeService runtimeService;

    private int sequence;

    @BeforeEach
    void seed() {
        EngineTestSupport.clearRuns(runtimeService);
        jdbc.execute("""
                TRUNCATE approval_task, approval_instance, outbox_event, idempotency_record,
                         approval_workflow_ref, form_schema, process_definition, approval_template,
                         webhook_subscription, webhook_delivery, approval_action, decision_rule
                         RESTART IDENTITY CASCADE
                """);
        jdbc.update("INSERT INTO approval_template (id, tenant_id, template_key, status) "
                + "VALUES ('tpl-1', ?, 'purchase_request', 'PUBLISHED')", TENANT);
        jdbc.update("INSERT INTO process_definition (id, template_id, version, bpmn_xml, checksum) "
                + "VALUES ('pd-1', 'tpl-1', 1, ?, 'chk')", TestBpmn.purchaseRequest());
        jdbc.update("INSERT INTO form_schema (id, template_id, version, json_schema) "
                + "VALUES ('fs-1', 'tpl-1', 1, '{}')");
        jdbc.update("""
                INSERT INTO decision_rule (id, tenant_id, template_key, priority, approvers)
                VALUES ('rule-default', ?, 'purchase_request', 100,
                        '[{"type":"USER","id":"u_2"},{"type":"USER","id":"u_3"}]'::jsonb)
                """, TENANT);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder asActor(MockHttpServletRequestBuilder request, String actorId) {
        return request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", actorId);
    }

    private String body(String bizKey, String extra) {
        return "{\"templateKey\":\"purchase_request\",\"externalBusinessKey\":\"" + bizKey + "\","
                + "\"variables\":{\"amount\":100,\"currency\":\"CNY\"}" + extra + "}";
    }

    private MockHttpServletRequestBuilder startRequest(String actorId, String extra) {
        String bizKey = "biz-" + (++sequence);
        return asActor(post("/v1/approval-instances"), actorId)
                .header("Idempotency-Key", "idem-" + bizKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(bizKey, extra));
    }

    private String startInstanceAs(String actorId) throws Exception {
        MvcResult result = mvc.perform(startRequest(actorId, "")).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private List<String> taskIds(String instanceId) {
        return jdbc.queryForList("SELECT id FROM approval_task WHERE tenant_id = ? AND instance_id = ? ORDER BY id",
                String.class, TENANT, instanceId);
    }

    private String taskStatus(String taskId) {
        return jdbc.queryForObject("SELECT status FROM approval_task WHERE id = ?", String.class, taskId);
    }

    private MockHttpServletRequestBuilder approve(String taskId, String actorId) {
        return asActor(post("/v1/tasks/" + taskId + "/approve"), actorId)
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    // ---- authentication ------------------------------------------------------------------------

    @Test
    void requestWithoutAnIdentityIs401WithAProblemBody() throws Exception {
        mvc.perform(get("/v1/approval-instances").header("X-Tenant-Id", TENANT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("approval.unauthenticated"));
    }

    @Test
    void actuatorHealthStaysOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    // ---- start: the initiator is the authenticated actor ---------------------------------------

    @Test
    void startUsesTheAuthenticatedActorAsInitiator() throws Exception {
        mvc.perform(startRequest("u_1", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.initiatorType").value("USER"))
                .andExpect(jsonPath("$.initiatorId").value("u_1"));
    }

    @Test
    void userCannotStartOnBehalfOfSomeoneElse() throws Exception {
        mvc.perform(startRequest("u_1", ",\"initiatorType\":\"USER\",\"initiatorId\":\"u_7\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("approval.forbidden"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_instance", Integer.class)).isZero();
    }

    @Test
    void systemActorMayStartOnBehalfOfAUser() throws Exception {
        mvc.perform(startRequest("svc-erp", ",\"initiatorType\":\"USER\",\"initiatorId\":\"u_7\"")
                        .header("X-Actor-Type", "SYSTEM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.initiatorId").value("u_7"));
    }

    @Test
    void startRequiresTheCreateAuthority() throws Exception {
        mvc.perform(startRequest("u_1", "").header("X-Actor-Authorities", "approval.request.read"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("approval.forbidden"));
    }

    // ---- approve / reject: only the assignee ---------------------------------------------------

    @Test
    void assigneeCanApproveTheirTask() throws Exception {
        String instanceId = startInstanceAs("u_1");
        String taskId = taskIds(instanceId).get(0);
        String assignee = jdbc.queryForObject("SELECT assignee_id FROM approval_task WHERE id = ?", String.class, taskId);

        mvc.perform(approve(taskId, assignee)).andExpect(status().isOk());

        assertThat(taskStatus(taskId)).isEqualTo("APPROVED");
    }

    @Test
    void nonAssigneeCannotApproveAndNothingChanges() throws Exception {
        String instanceId = startInstanceAs("u_1");
        String taskId = taskIds(instanceId).get(0);

        mvc.perform(approve(taskId, "u_9"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("approval.forbidden"));

        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_action WHERE action = 'APPROVED'",
                Integer.class)).isZero();
    }

    @Test
    void theBodyCanNoLongerNameTheActor() throws Exception {
        String instanceId = startInstanceAs("u_1");
        String taskId = taskIds(instanceId).get(0);
        String assignee = jdbc.queryForObject("SELECT assignee_id FROM approval_task WHERE id = ?", String.class, taskId);

        // u_9 claims to be the assignee in the body — ignored; the authenticated actor is u_9.
        mvc.perform(asActor(post("/v1/tasks/" + taskId + "/approve"), "u_9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorType\":\"USER\",\"actorId\":\"" + assignee + "\"}"))
                .andExpect(status().isForbidden());

        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
    }

    @Test
    void approveRequiresTheApproveAuthorityAndRejectRequiresTheRejectAuthority() throws Exception {
        String instanceId = startInstanceAs("u_1");
        String taskId = taskIds(instanceId).get(0);
        String assignee = jdbc.queryForObject("SELECT assignee_id FROM approval_task WHERE id = ?", String.class, taskId);

        mvc.perform(approve(taskId, assignee).header("X-Actor-Authorities", "approval.request.reject"))
                .andExpect(status().isForbidden());
        mvc.perform(asActor(post("/v1/tasks/" + taskId + "/reject"), assignee)
                        .header("X-Actor-Authorities", "approval.request.approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
    }

    @Test
    void approvingAnAlreadyApprovedTaskIsAClientErrorNotA500() throws Exception {
        String instanceId = startInstanceAs("u_1");
        String taskId = taskIds(instanceId).get(0);
        String assignee = jdbc.queryForObject("SELECT assignee_id FROM approval_task WHERE id = ?", String.class, taskId);

        mvc.perform(approve(taskId, assignee)).andExpect(status().isOk());
        mvc.perform(approve(taskId, assignee))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.illegal_state"));
    }

    @Test
    void concurrentApprovalsOfOneTaskHaveExactlyOneWinner() throws Exception {
        String instanceId = startInstanceAs("u_1");
        String taskId = taskIds(instanceId).get(0);
        String assignee = jdbc.queryForObject("SELECT assignee_id FROM approval_task WHERE id = ?", String.class, taskId);

        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<Integer> call = () -> {
                    go.await();
                    return mvc.perform(approve(taskId, assignee)).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            int ok = 0;
            for (Future<Integer> result : results) {
                int code = result.get();
                if (code == 200) {
                    ok++;
                } else {
                    assertThat(code).as("losers get a client error, never a 5xx").isIn(400, 409);
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_action WHERE action = 'APPROVED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void twoApproversApprovingTheirSiblingTasksAtTheSameTimeEndWithTheInstanceApproved() throws Exception {
        String instanceId = startInstanceAs("u_1");
        List<String> tasks = taskIds(instanceId);
        assertThat(tasks).hasSize(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (String taskId : tasks) {
                String assignee = jdbc.queryForObject("SELECT assignee_id FROM approval_task WHERE id = ?",
                        String.class, taskId);
                results.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(approve(taskId, assignee)).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                // The run is advanced one approver after the other: neither may fail or be lost.
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT status FROM approval_instance WHERE id = ?", String.class, instanceId))
                .isEqualTo("APPROVED");
        tasks.forEach(task -> assertThat(taskStatus(task)).isEqualTo("APPROVED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE type = 'approval.instance.approved'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void anotherTenantCannotSeeOrActOnTheTask() throws Exception {
        String instanceId = startInstanceAs("u_1");
        String taskId = taskIds(instanceId).get(0);
        String assignee = jdbc.queryForObject("SELECT assignee_id FROM approval_task WHERE id = ?", String.class, taskId);

        mvc.perform(post("/v1/tasks/" + taskId + "/approve")
                        .header("X-Tenant-Id", OTHER_TENANT).header("X-Actor-Id", assignee)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
    }

    // ---- withdraw: only the initiator, whole instance ------------------------------------------

    @Test
    void onlyTheInitiatorCanWithdrawAndAllTasksAreCancelled() throws Exception {
        String instanceId = startInstanceAs("u_1");
        List<String> tasks = taskIds(instanceId);
        String assignee = jdbc.queryForObject("SELECT assignee_id FROM approval_task WHERE id = ?",
                String.class, tasks.get(0));

        // An approver (and any other user) cannot withdraw someone else's request.
        mvc.perform(asActor(post("/v1/approval-instances/" + instanceId + "/withdraw"), assignee)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(asActor(post("/v1/approval-instances/" + instanceId + "/withdraw"), "u_1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"oops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        tasks.forEach(task -> assertThat(taskStatus(task)).isEqualTo("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT comment FROM approval_action WHERE action = 'WITHDRAWN'",
                String.class)).isEqualTo("oops");
    }

    // ---- admin endpoints -----------------------------------------------------------------------

    @Test
    void auditEndpointRequiresTheAuditAuthority() throws Exception {
        mvc.perform(asActor(get("/v1/admin/audit-records"), "u_1")
                        .header("X-Actor-Authorities", "approval.request.read"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("approval.forbidden"));

        mvc.perform(asActor(get("/v1/admin/audit-records"), "u_1")
                        .header("X-Actor-Authorities", "approval.request.audit"))
                .andExpect(status().isOk());
    }

    @Test
    void templateAndRuleAdminRequireTheirManageAuthorities() throws Exception {
        String readOnly = "approval.request.read";

        mvc.perform(asActor(get("/v1/admin/templates"), "u_1").header("X-Actor-Authorities", readOnly))
                .andExpect(status().isForbidden());
        mvc.perform(asActor(post("/v1/admin/templates"), "u_1").header("X-Actor-Authorities", readOnly)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"templateKey\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(asActor(get("/v1/admin/decision-rules"), "u_1").header("X-Actor-Authorities", readOnly))
                .andExpect(status().isForbidden());
        mvc.perform(asActor(get("/v1/admin/webhook-deliveries"), "u_1").header("X-Actor-Authorities", readOnly))
                .andExpect(status().isForbidden());

        mvc.perform(asActor(get("/v1/admin/templates"), "u_1").header("X-Actor-Authorities", "approval.template.manage"))
                .andExpect(status().isOk());
    }

    @Test
    void readEndpointsRequireTheReadAuthority() throws Exception {
        mvc.perform(asActor(get("/v1/approval-instances"), "u_1").header("X-Actor-Authorities", "approval.request.create"))
                .andExpect(status().isForbidden());
        mvc.perform(asActor(get("/v1/tasks"), "u_1").header("X-Actor-Authorities", "approval.request.create"))
                .andExpect(status().isForbidden());
        mvc.perform(asActor(get("/v1/tasks"), "u_1")).andExpect(status().isOk());
    }
}
