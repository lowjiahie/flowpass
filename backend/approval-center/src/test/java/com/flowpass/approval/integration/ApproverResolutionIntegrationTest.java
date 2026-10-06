package com.flowpass.approval.integration;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who gets the tasks, end to end: a rule mixing the initiator's manager, a role and a form field resolves to
 * concrete tasks; a role task is acted on by any current member (first wins) but never by the initiator;
 * "my tasks" includes role tasks; an unresolvable approver routes the request to manual review.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class ApproverResolutionIntegrationTest {

    private static final String TENANT = "t_res";
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
    private RuntimeService runtimeService;

    private int sequence;

    @BeforeEach
    void seed() {
        EngineTestSupport.clearRuns(runtimeService);
        jdbc.execute("""
                TRUNCATE approval_task, approval_instance, outbox_event, idempotency_record,
                         approval_workflow_ref, form_schema, process_definition, approval_template,
                         webhook_subscription, webhook_delivery, approval_action, decision_rule,
                         directory_user, directory_department, directory_role_member
                         RESTART IDENTITY CASCADE
                """);
        TestBpmn.seedPublished(jdbc, TENANT, KEY, 1);

        // u_1 reports to u_boss and sits in department d1 (head: u_head); finance = u_fin1, u_fin2.
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id, manager_id, department_id) VALUES (?, 'u_1', 'u_boss', 'd1')", TENANT);
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id) VALUES (?, 'u_boss')", TENANT);
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id) VALUES (?, 'u_2')", TENANT); // no manager on record
        jdbc.update("INSERT INTO directory_department (tenant_id, department_id, head_user_id) VALUES (?, 'd1', 'u_head')", TENANT);
        jdbc.update("INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES (?, 'finance', 'u_fin1')", TENANT);
        jdbc.update("INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES (?, 'finance', 'u_fin2')", TENANT);

        // Rule: the initiator's manager, any member of finance, and the reviewer named on the form.
        jdbc.update("""
                INSERT INTO decision_rule (id, tenant_id, template_key, priority, approvers)
                VALUES ('rule-mixed', ?, 'purchase_request', 10,
                  '[{"type":"INITIATOR_MANAGER","id":"initiator"},{"type":"ROLE","id":"finance"},{"type":"FORM_FIELD","id":"reviewer"}]'::jsonb)
                """, TENANT);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String actor) {
        return request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", actor);
    }

    private String start(String initiator, String variablesJson) throws Exception {
        String bizKey = "biz-" + (++sequence);
        String body = mvc.perform(as(post("/v1/approval-instances"), initiator)
                        .header("Idempotency-Key", "idem-" + bizKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"externalBusinessKey\":\"" + bizKey
                                + "\",\"variables\":" + variablesJson + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String startMixed() throws Exception {
        return start("u_1", "{\"amount\":100,\"currency\":\"CNY\",\"reviewer\":\"u_rev\"}");
    }

    private List<String> assignees(String instanceId) {
        return jdbc.queryForList("SELECT assignee_type || ':' || assignee_id FROM approval_task WHERE instance_id = ?",
                String.class, instanceId);
    }

    private String taskOf(String instanceId, String assignee) {
        String[] parts = assignee.split(":");
        return jdbc.queryForObject("SELECT id FROM approval_task WHERE instance_id = ? AND assignee_type = ? AND assignee_id = ?",
                String.class, instanceId, parts[0], parts[1]);
    }

    private MockHttpServletRequestBuilder approve(String taskId, String actor) {
        return as(post("/v1/tasks/" + taskId + "/approve"), actor).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private String taskStatus(String taskId) {
        return jdbc.queryForObject("SELECT status FROM approval_task WHERE id = ?", String.class, taskId);
    }

    // ---- resolution ----------------------------------------------------------------------------------

    @Test
    void aMixedRuleResolvesToTheManagerARoleAndTheFormFieldUser() throws Exception {
        String instanceId = startMixed();

        assertThat(assignees(instanceId)).containsExactlyInAnyOrder("USER:u_boss", "ROLE:finance", "USER:u_rev");
        // The engine holds the same three tasks, assigned the same way.
        assertThat(jdbc.queryForList("""
                SELECT t.ASSIGNEE_ FROM ACT_RU_TASK t JOIN approval_workflow_ref r ON t.PROC_INST_ID_ = r.process_instance_id
                WHERE r.instance_id = ?
                """, String.class, instanceId)).containsExactlyInAnyOrder("USER:u_boss", "ROLE:finance", "USER:u_rev");
    }

    @Test
    void theInitiatorIsLeftOutOfTheirOwnApprovers() throws Exception {
        // u_boss starts a request; their own manager lookup finds none, but the role and the form user remain —
        // so make the rule name the initiator explicitly to see the self-skip.
        jdbc.update("DELETE FROM decision_rule");
        jdbc.update("""
                INSERT INTO decision_rule (id, tenant_id, template_key, priority, approvers)
                VALUES ('rule-self', ?, 'purchase_request', 10,
                  '[{"type":"USER","id":"u_1"},{"type":"USER","id":"u_2"}]'::jsonb)
                """, TENANT);

        String instanceId = start("u_1", "{}");

        assertThat(assignees(instanceId)).containsExactly("USER:u_2");
    }

    @Test
    void anUnresolvableApproverRoutesTheWholeRequestToManualReviewAndSaysWhy() throws Exception {
        // u_2 has no manager on record: the request must not run with only the role and the form user.
        String instanceId = start("u_2", "{\"reviewer\":\"u_rev\"}");

        assertThat(assignees(instanceId)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT t.TASK_DEF_KEY_ FROM ACT_RU_TASK t JOIN approval_workflow_ref r ON t.PROC_INST_ID_ = r.process_instance_id
                WHERE r.instance_id = ?
                """, String.class, instanceId)).containsExactly("manualReview");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM approval_instance WHERE id = ?", String.class, instanceId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "SELECT comment FROM approval_action WHERE instance_id = ? AND action = 'STARTED'", String.class, instanceId))
                .contains("manual review").contains("no manager recorded for u_2");
    }

    @Test
    void aMissingFormFieldAlsoRoutesToManualReview() throws Exception {
        String instanceId = start("u_1", "{\"amount\":100,\"currency\":\"CNY\"}");

        assertThat(assignees(instanceId)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT comment FROM approval_action WHERE instance_id = ? AND action = 'STARTED'", String.class, instanceId))
                .contains("form field 'reviewer'");
    }

    // ---- acting on a role task -----------------------------------------------------------------------

    @Test
    void anyCurrentMemberOfTheRoleCanApproveAndTheFirstOneWins() throws Exception {
        String instanceId = startMixed();
        String roleTask = taskOf(instanceId, "ROLE:finance");

        mvc.perform(approve(roleTask, "u_fin2")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
        assertThat(taskStatus(roleTask)).isEqualTo("APPROVED");

        // The other member arrives second: the task is already done.
        mvc.perform(approve(roleTask, "u_fin1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.illegal_state"));
        assertThat(jdbc.queryForObject("SELECT actor_id FROM approval_action WHERE task_id = ?", String.class, roleTask))
                .isEqualTo("u_fin2");
    }

    @Test
    void theRequestIsApprovedOnceTheManagerTheRoleAndTheReviewerAllApproved() throws Exception {
        String instanceId = startMixed();

        mvc.perform(approve(taskOf(instanceId, "USER:u_boss"), "u_boss")).andExpect(status().isOk());
        mvc.perform(approve(taskOf(instanceId, "ROLE:finance"), "u_fin1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        mvc.perform(approve(taskOf(instanceId, "USER:u_rev"), "u_rev")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void someoneWithoutTheRoleCannotActOnItsTask() throws Exception {
        String instanceId = startMixed();
        String roleTask = taskOf(instanceId, "ROLE:finance");

        mvc.perform(approve(roleTask, "u_9")).andExpect(status().isForbidden());
        // Knowing the role name is not enough, and neither is another task's assignee.
        mvc.perform(approve(roleTask, "u_boss")).andExpect(status().isForbidden());
        assertThat(taskStatus(roleTask)).isEqualTo("PENDING");
    }

    @Test
    void membershipIsCheckedWhenActingSoALaterJoinerCanAndALeaverCannot() throws Exception {
        String instanceId = startMixed();
        String roleTask = taskOf(instanceId, "ROLE:finance");
        jdbc.update("INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES (?, 'finance', 'u_new')", TENANT);
        jdbc.update("DELETE FROM directory_role_member WHERE role_name = 'finance' AND user_id = 'u_fin1'");

        mvc.perform(approve(roleTask, "u_fin1")).andExpect(status().isForbidden());
        mvc.perform(approve(roleTask, "u_new")).andExpect(status().isOk());
    }

    @Test
    void theInitiatorCannotApproveTheirOwnRequestThroughARoleTheyHold() throws Exception {
        jdbc.update("INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES (?, 'finance', 'u_1')", TENANT);
        String instanceId = startMixed();
        String roleTask = taskOf(instanceId, "ROLE:finance");

        mvc.perform(approve(roleTask, "u_1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("own request")));
        assertThat(taskStatus(roleTask)).isEqualTo("PENDING");
    }

    @Test
    void aRoleMemberRejectingEndsTheRequestAndClosesTheOtherTasks() throws Exception {
        String instanceId = startMixed();

        mvc.perform(as(post("/v1/tasks/" + taskOf(instanceId, "ROLE:finance") + "/reject"), "u_fin1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"over budget\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(jdbc.queryForList("SELECT status FROM approval_task WHERE instance_id = ? ORDER BY status",
                String.class, instanceId)).containsExactlyInAnyOrder("REJECTED", "CANCELLED", "CANCELLED");
    }

    // ---- my tasks ------------------------------------------------------------------------------------

    @Test
    void myTasksIncludeMyOwnAndMyRolesTasksAndNothingElse() throws Exception {
        startMixed();

        mvc.perform(as(get("/v1/tasks?mine=true&status=PENDING"), "u_fin1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].assigneeType").value("ROLE"))
                .andExpect(jsonPath("$[0].assigneeId").value("finance"));
        mvc.perform(as(get("/v1/tasks?mine=true"), "u_boss"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].assigneeId").value("u_boss"));
        mvc.perform(as(get("/v1/tasks?mine=true"), "u_9"))
                .andExpect(jsonPath("$.length()").value(0));
        // Without the filter a reader sees every task of the tenant.
        mvc.perform(as(get("/v1/tasks"), "u_9")).andExpect(jsonPath("$.length()").value(3));
    }

    // ---- rule definition and preview -------------------------------------------------------------------

    @Test
    void aRuleWithAnUnknownApproverTypeIsRefused() throws Exception {
        mvc.perform(as(post("/v1/admin/decision-rules"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"approvers\":[{\"type\":\"TEAM_LEAD\",\"id\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.validation_failed"))
                .andExpect(jsonPath("$.detail").value(containsString("TEAM_LEAD")));
    }

    @Test
    void aRuleWithRelativeAndGroupApproversCanBeDefined() throws Exception {
        mvc.perform(as(post("/v1/admin/decision-rules"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"other\",\"approvers\":[{\"type\":\"DEPARTMENT_HEAD\",\"id\":\"initiator\"},"
                                + "{\"type\":\"ROLE\",\"id\":\"legal\"}]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void thePreviewShowsTheResolvedApproversRelativeToTheNamedInitiator() throws Exception {
        mvc.perform(as(post("/v1/admin/decision-rules/preview"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"initiatorId\":\"u_1\",\"variables\":{\"reviewer\":\"u_rev\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvers.length()").value(3))
                .andExpect(jsonPath("$.problems.length()").value(0));
    }

    @Test
    void thePreviewExplainsWhyAStartWouldGoToManualReview() throws Exception {
        mvc.perform(as(post("/v1/admin/decision-rules/preview"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"initiatorId\":\"u_2\",\"variables\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvers.length()").value(0))
                .andExpect(jsonPath("$.problems.length()").value(2));
    }

    @Test
    void theDirectoryIsScopedToTheTenant() throws Exception {
        // Another tenant has a manager for u_2; it must not leak into this tenant's resolution.
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id, manager_id) VALUES ('t_other', 'u_2', 'u_other_boss')");
        String instanceId = start("u_2", "{\"reviewer\":\"u_rev\"}");

        assertThat(assignees(instanceId)).isEmpty();
    }
}
