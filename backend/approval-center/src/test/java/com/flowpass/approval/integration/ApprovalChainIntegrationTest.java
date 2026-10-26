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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Multi-round approval end to end: the amount decides how long the chain is (manager; then finance; then the
 * manager's manager), each round opens only when the previous one is done, and a rejection or a withdrawal stops
 * the chain wherever it is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class ApprovalChainIntegrationTest {

    private static final String TENANT = "t_chain";
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

        // u_1 -> u_boss -> u_director.  finance = u_fin1, u_fin2.  u_lone reports to u_lonely, who has no manager.
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id, manager_id) VALUES (?, 'u_1', 'u_boss')", TENANT);
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id, manager_id) VALUES (?, 'u_boss', 'u_director')", TENANT);
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id) VALUES (?, 'u_director')", TENANT);
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id, manager_id) VALUES (?, 'u_lone', 'u_lonely')", TENANT);
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id) VALUES (?, 'u_lonely')", TENANT);
        jdbc.update("INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES (?, 'finance', 'u_fin1')", TENANT);
        jdbc.update("INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES (?, 'finance', 'u_fin2')", TENANT);

        // The longer the amount, the longer the chain.
        defineRule(10, "amount >= 10000", List.of(
                approver("INITIATOR_MANAGER", "1", 1), approver("ROLE", "finance", 2), approver("INITIATOR_MANAGER", "2", 3)));
        defineRule(20, "amount >= 1000", List.of(approver("INITIATOR_MANAGER", "1", 1), approver("ROLE", "finance", 2)));
        defineRule(100, null, List.of(approver("INITIATOR_MANAGER", "1", 1)));
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

    private void defineRule(int priority, String expression, List<Map<String, Object>> approvers) throws Exception {
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

    private String start(String initiator, int amount) throws Exception {
        String bizKey = "biz-" + (++sequence);
        String body = mvc.perform(as(post("/v1/approval-instances"), initiator)
                        .header("Idempotency-Key", "idem-" + bizKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"externalBusinessKey\":\"" + bizKey
                                + "\",\"variables\":{\"amount\":" + amount + ",\"currency\":\"MYR\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** The open tasks of an instance as "ASSIGNEE_TYPE:assignee_id", oldest first. */
    private List<String> open(String instanceId) {
        return jdbc.queryForList("""
                SELECT assignee_type || ':' || assignee_id FROM approval_task
                WHERE instance_id = ? AND status IN ('PENDING', 'CLAIMED') ORDER BY id
                """, String.class, instanceId);
    }

    private List<String> allTasks(String instanceId) {
        return jdbc.queryForList("SELECT assignee_type || ':' || assignee_id FROM approval_task WHERE instance_id = ?",
                String.class, instanceId);
    }

    private String taskOf(String instanceId, String assignee) {
        String[] parts = assignee.split(":");
        return jdbc.queryForObject("SELECT id FROM approval_task WHERE instance_id = ? AND assignee_type = ? AND assignee_id = ?",
                String.class, instanceId, parts[0], parts[1]);
    }

    private String approve(String instanceId, String assignee, String actor) throws Exception {
        String body = mvc.perform(as(post("/v1/tasks/" + taskOf(instanceId, assignee) + "/approve"), actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.status");
    }

    private String instanceStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM approval_instance WHERE id = ?", String.class, id);
    }

    // ---- the chain -----------------------------------------------------------------------------------

    @Test
    void aLargeRequestWalksThroughThreeRoundsEachOpeningOnlyWhenThePreviousIsDone() throws Exception {
        String id = start("u_1", 20000);

        // Round 1 only: the manager. Finance and the director have nothing yet.
        assertThat(open(id)).containsExactly("USER:u_boss");
        assertThat(allTasks(id)).hasSize(1);
        mvc.perform(as(get("/v1/tasks?mine=true&status=PENDING"), "u_fin1")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/v1/tasks?mine=true&status=PENDING"), "u_director")).andExpect(jsonPath("$.length()").value(0));

        // Round 1 done -> round 2 (the finance role) opens.
        assertThat(approve(id, "USER:u_boss", "u_boss")).isEqualTo("RUNNING");
        assertThat(open(id)).containsExactly("ROLE:finance");
        mvc.perform(as(get("/v1/tasks?mine=true&status=PENDING"), "u_fin1"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].assigneeId").value("finance"));
        mvc.perform(as(get("/v1/tasks?mine=true&status=PENDING"), "u_boss")).andExpect(jsonPath("$.length()").value(0));

        // Round 2 done (any finance member) -> round 3 (the manager's manager) opens.
        assertThat(approve(id, "ROLE:finance", "u_fin2")).isEqualTo("RUNNING");
        assertThat(open(id)).containsExactly("USER:u_director");

        // The last round completes the request.
        assertThat(approve(id, "USER:u_director", "u_director")).isEqualTo("APPROVED");
        assertThat(open(id)).isEmpty();
        assertThat(instanceStatus(id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_action WHERE instance_id = ? AND action = 'APPROVED'",
                Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE type = 'approval.instance.approved'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void aMediumRequestHasTwoRoundsAndASmallOneJustOne() throws Exception {
        String medium = start("u_1", 2000);
        assertThat(open(medium)).containsExactly("USER:u_boss");
        approve(medium, "USER:u_boss", "u_boss");
        assertThat(open(medium)).containsExactly("ROLE:finance");
        assertThat(approve(medium, "ROLE:finance", "u_fin1")).isEqualTo("APPROVED");

        String small = start("u_1", 50);
        assertThat(open(small)).containsExactly("USER:u_boss");
        assertThat(approve(small, "USER:u_boss", "u_boss")).isEqualTo("APPROVED");
    }

    @Test
    void aRoundTwoApproverCannotJumpTheQueueAndTheWrongPersonCannotActInTheirRound() throws Exception {
        String id = start("u_1", 20000);

        // Finance has no task yet, and cannot touch the manager's.
        mvc.perform(as(post("/v1/tasks/" + taskOf(id, "USER:u_boss") + "/approve"), "u_fin1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        approve(id, "USER:u_boss", "u_boss");
        // Round 2 is open: the director (round 3) and the manager (already done) cannot act on it.
        mvc.perform(as(post("/v1/tasks/" + taskOf(id, "ROLE:finance") + "/approve"), "u_director")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(open(id)).containsExactly("ROLE:finance");
    }

    @Test
    void aRejectionInTheSecondRoundEndsTheChainAndTheThirdRoundNeverOpens() throws Exception {
        String id = start("u_1", 20000);
        approve(id, "USER:u_boss", "u_boss");

        mvc.perform(as(post("/v1/tasks/" + taskOf(id, "ROLE:finance") + "/reject"), "u_fin1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"over budget\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(instanceStatus(id)).isEqualTo("REJECTED");
        assertThat(allTasks(id)).as("the director never got a task").containsExactlyInAnyOrder("USER:u_boss", "ROLE:finance");
        assertThat(open(id)).isEmpty();
    }

    @Test
    void withdrawingInTheMiddleOfTheChainCancelsTheOpenTaskAndStopsTheRest() throws Exception {
        String id = start("u_1", 20000);
        approve(id, "USER:u_boss", "u_boss");

        mvc.perform(as(post("/v1/approval-instances/" + id + "/withdraw"), "u_1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));

        assertThat(jdbc.queryForObject("SELECT status FROM approval_task WHERE id = ?", String.class,
                taskOf(id, "ROLE:finance"))).isEqualTo("CANCELLED");
        assertThat(allTasks(id)).doesNotContain("USER:u_director");
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceTenantId(TENANT).count()).isZero();
    }

    // ---- resolution of the whole chain ---------------------------------------------------------------

    @Test
    void ifAnyRoundCannotBeResolvedTheWholeRequestGoesToManualReviewAtStart() throws Exception {
        // u_lone's manager has no manager, so "level 2" of the big chain is unresolvable.
        String id = start("u_lone", 20000);

        assertThat(allTasks(id)).as("not even the first round proceeds with a broken chain").isEmpty();
        assertThat(jdbc.queryForObject("SELECT comment FROM approval_action WHERE instance_id = ? AND action = 'STARTED'",
                String.class, id)).contains("manual review").contains("reporting line");
        assertThat(instanceStatus(id)).isEqualTo("RUNNING");
    }

    @Test
    void theSameRequestWithASmallAmountNeedsOnlyTheResolvableRound() throws Exception {
        assertThat(open(start("u_lone", 50))).containsExactly("USER:u_lonely");
    }

    @Test
    void aPersonNamedInTwoRoundsIsAskedOnlyOnce() throws Exception {
        jdbc.update("DELETE FROM decision_rule");
        defineRule(10, null, List.of(approver("INITIATOR_MANAGER", "1", 1), approver("USER", "u_boss", 2),
                approver("ROLE", "finance", 2)));

        String id = start("u_1", 100);

        assertThat(open(id)).containsExactly("USER:u_boss");
        assertThat(approve(id, "USER:u_boss", "u_boss")).isEqualTo("RUNNING");
        assertThat(open(id)).as("u_boss is not asked again; the finance role is round 2").containsExactly("ROLE:finance");
    }

    @Test
    void theInitiatorIsLeftOutOfEveryRound() throws Exception {
        jdbc.update("DELETE FROM decision_rule");
        defineRule(10, null, List.of(approver("USER", "u_1", 1), approver("USER", "u_boss", 2)));

        String id = start("u_1", 100);

        assertThat(open(id)).as("round 1 was only the initiator, so round 2 becomes the first round")
                .containsExactly("USER:u_boss");
        assertThat(approve(id, "USER:u_boss", "u_boss")).isEqualTo("APPROVED");
    }

    // ---- definition and preview ----------------------------------------------------------------------

    @Test
    void thePreviewShowsTheChainForTheAmountRoundByRound() throws Exception {
        mvc.perform(as(post("/v1/admin/decision-rules/preview"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"initiatorId\":\"u_1\",\"variables\":{\"amount\":20000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rounds.length()").value(3))
                .andExpect(jsonPath("$.rounds[0][0].id").value("u_boss"))
                .andExpect(jsonPath("$.rounds[1][0].id").value("finance"))
                .andExpect(jsonPath("$.rounds[1][0].type").value("ROLE"))
                .andExpect(jsonPath("$.rounds[2][0].id").value("u_director"));
        mvc.perform(as(post("/v1/admin/decision-rules/preview"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"initiatorId\":\"u_1\",\"variables\":{\"amount\":50}}"))
                .andExpect(jsonPath("$.rounds.length()").value(1));
        mvc.perform(as(post("/v1/admin/decision-rules/preview"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"initiatorId\":\"u_lone\",\"variables\":{\"amount\":20000}}"))
                .andExpect(jsonPath("$.rounds.length()").value(0))
                .andExpect(jsonPath("$.problems[0]").value(containsString("reporting line")));
    }

    @Test
    void theRuleListShowsEachApproversRound() throws Exception {
        mvc.perform(as(get("/v1/admin/decision-rules?templateKey=" + KEY), "u_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].approvers.length()").value(3))
                .andExpect(jsonPath("$[0].approvers[0].round").value(1))
                .andExpect(jsonPath("$[0].approvers[1].round").value(2))
                .andExpect(jsonPath("$[0].approvers[2].round").value(3));
    }

    @Test
    void aRoundOutsideOneToFiveIsRefusedWhenTheRuleIsDefined() throws Exception {
        List<Map<String, Object>> bad = new ArrayList<>(List.of(approver("USER", "u_x", 7)));

        mvc.perform(as(post("/v1/admin/decision-rules"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("templateKey", KEY, "approvers", bad))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.validation_failed"))
                .andExpect(jsonPath("$.detail").value(containsString("between 1 and 5")));
    }

    @Test
    void aRuleWithoutRoundNumbersIsOneRoundAsBefore() throws Exception {
        jdbc.update("DELETE FROM decision_rule");
        mvc.perform(as(post("/v1/admin/decision-rules"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"approvers\":[{\"type\":\"USER\",\"id\":\"u_a\"},"
                                + "{\"type\":\"USER\",\"id\":\"u_b\"}]}"))
                .andExpect(status().isCreated());

        String id = start("u_1", 100);

        assertThat(open(id)).containsExactlyInAnyOrder("USER:u_a", "USER:u_b");
        approve(id, "USER:u_a", "u_a");
        assertThat(approve(id, "USER:u_b", "u_b")).isEqualTo("APPROVED");
    }
}
