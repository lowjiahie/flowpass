package com.flowpass.approval.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowpass.approval.application.sla.ProcessSlaHandler;
import com.flowpass.approval.support.EngineTestSupport;
import com.flowpass.approval.support.MutableClock;
import com.flowpass.approval.support.TestBpmn;
import com.flowpass.approval.support.TestClockConfig;
import com.jayway.jsonpath.JsonPath;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deadlines, reminders, escalation and delegation through the HTTP API, with time under the test's control. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestClockConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class SlaAndDelegationIntegrationTest {

    private static final String TENANT = "t_sla";
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
    @Autowired
    private MutableClock clock;
    @Autowired
    private ProcessSlaHandler slaHandler;

    private int sequence;
    private Instant t0;

    @BeforeEach
    void seed() throws Exception {
        EngineTestSupport.clearRuns(runtimeService);
        jdbc.execute("""
                TRUNCATE approval_task, approval_instance, outbox_event, idempotency_record,
                         approval_workflow_ref, form_schema, process_definition, approval_template,
                         webhook_subscription, webhook_delivery, approval_action, decision_rule,
                         directory_user, directory_department, directory_role_member,
                         sla_policy, delegation
                         RESTART IDENTITY CASCADE
                """);
        t0 = Instant.parse("2026-06-01T09:00:00Z");
        clock.set(t0);
        TestBpmn.seedPublished(jdbc, TENANT, KEY, 1);
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id, manager_id) VALUES (?, 'u_a', 'u_boss')", TENANT);
        jdbc.update("INSERT INTO directory_user (tenant_id, user_id) VALUES (?, 'u_boss')", TENANT);
        mvc.perform(as(post("/v1/admin/decision-rules"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateKey\":\"" + KEY + "\",\"priority\":100,\"approvers\":"
                        + "[{\"type\":\"USER\",\"id\":\"u_a\",\"round\":1}]}")).andExpect(status().isCreated());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String actor) {
        return request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", actor);
    }

    private void sla(String onOverdue, String escalateTo) throws Exception {
        String body = "{\"dueAfterHours\":24,\"remindBeforeHours\":6,\"onOverdue\":\"" + onOverdue + "\""
                + (escalateTo == null ? "" : ",\"escalateToUser\":\"" + escalateTo + "\"") + "}";
        mvc.perform(as(put("/v1/admin/templates/" + KEY + "/sla"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
    }

    private String start() throws Exception {
        String bizKey = "biz-" + (++sequence);
        String body = mvc.perform(as(post("/v1/approval-instances"), "u_1").header("Idempotency-Key", "idem-" + bizKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"externalBusinessKey\":\"" + bizKey
                                + "\",\"variables\":{\"amount\":100,\"currency\":\"MYR\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String assignee(String instanceId) {
        return jdbc.queryForObject("SELECT assignee_id FROM approval_task WHERE instance_id = ? AND status IN ('PENDING','CLAIMED')",
                String.class, instanceId);
    }

    private int events(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE type = ?", Integer.class, type);
    }

    private int audits(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM approval_action WHERE action = ?", Integer.class, action);
    }

    private List<String> engineAssignees(String instanceId) {
        return jdbc.queryForList("""
                SELECT t.ASSIGNEE_ FROM ACT_RU_TASK t JOIN approval_workflow_ref r ON t.PROC_INST_ID_ = r.process_instance_id
                WHERE r.instance_id = ? ORDER BY t.ASSIGNEE_
                """, String.class, instanceId);
    }

    // ---- SLA -------------------------------------------------------------------------------------------

    @Test
    void aTaskGetsItsDeadlinesFromTheTemplateSla() throws Exception {
        sla("NOTIFY_ONLY", null);
        String instance = start();

        mvc.perform(as(get("/v1/tasks?mine=true&status=PENDING"), "u_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dueAt").value(t0.plus(Duration.ofHours(24)).toString()));
    }

    @Test
    void aReminderGoesOutOnceWhenItsTimeComes() throws Exception {
        sla("NOTIFY_ONLY", null);
        start();

        clock.set(t0.plus(Duration.ofHours(10)));
        assertThat(slaHandler.run(clock.instant(), 100).reminded()).isZero();

        clock.set(t0.plus(Duration.ofHours(19)));
        assertThat(slaHandler.run(clock.instant(), 100).reminded()).isEqualTo(1);
        assertThat(slaHandler.run(clock.instant(), 100).reminded()).as("not reminded twice").isZero();
        assertThat(events("approval.task.reminded")).isEqualTo(1);
    }

    @Test
    void notifyOnlyLeavesTheTaskWhereItIsButRecordsTheOverdue() throws Exception {
        sla("NOTIFY_ONLY", null);
        String instance = start();

        clock.set(t0.plus(Duration.ofHours(25)));
        ProcessSlaHandler.Summary summary = slaHandler.run(clock.instant(), 100);

        assertThat(summary.overdue()).isEqualTo(1);
        assertThat(summary.escalated()).isZero();
        assertThat(assignee(instance)).isEqualTo("u_a");
        assertThat(events("approval.task.overdue")).isEqualTo(1);
        assertThat(audits("OVERDUE")).isEqualTo(1);
        assertThat(slaHandler.run(clock.instant(), 100).overdue()).as("handled once").isZero();
    }

    @Test
    void anOverdueTaskIsEscalatedToTheManagerInTheBusinessViewAndTheEngine() throws Exception {
        sla("ESCALATE_TO_MANAGER", null);
        String instance = start();

        clock.set(t0.plus(Duration.ofHours(25)));
        ProcessSlaHandler.Summary summary = slaHandler.run(clock.instant(), 100);

        assertThat(summary.escalated()).isEqualTo(1);
        assertThat(assignee(instance)).isEqualTo("u_boss");
        assertThat(engineAssignees(instance)).containsExactly("USER:u_boss");
        assertThat(events("approval.task.escalated")).isEqualTo(1);
        assertThat(audits("ESCALATED")).isEqualTo(1);
        assertThat(slaHandler.run(clock.instant(), 100).escalated()).as("idempotent").isZero();

        // the new owner can finish it
        String task = jdbc.queryForObject("SELECT id FROM approval_task WHERE instance_id = ?", String.class, instance);
        mvc.perform(as(post("/v1/tasks/" + task + "/approve"), "u_boss").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void aTemplateWithoutSlaNeverTimesOut() throws Exception {
        String instance = start();

        clock.set(t0.plus(Duration.ofDays(60)));

        assertThat(slaHandler.run(clock.instant(), 100)).isEqualTo(new ProcessSlaHandler.Summary(0, 0, 0, 0));
        assertThat(assignee(instance)).isEqualTo("u_a");
    }

    @Test
    void aFinishedRequestIsNotChased() throws Exception {
        sla("ESCALATE_TO_MANAGER", null);
        String instance = start();
        mvc.perform(as(post("/v1/approval-instances/" + instance + "/withdraw"), "u_1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"no longer needed\"}")).andExpect(status().isOk());

        clock.set(t0.plus(Duration.ofHours(30)));

        assertThat(slaHandler.run(clock.instant(), 100).escalated()).isZero();
        assertThat(events("approval.task.escalated")).isZero();
    }

    @Test
    void theSlaCanBeReadAndRemovedAndIsValidated() throws Exception {
        mvc.perform(as(put("/v1/admin/templates/" + KEY + "/sla"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAfterHours\":24,\"onOverdue\":\"ESCALATE_TO_USER\"}")).andExpect(status().isBadRequest());
        mvc.perform(as(put("/v1/admin/templates/" + KEY + "/sla"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAfterHours\":24,\"remindBeforeHours\":24,\"onOverdue\":\"NOTIFY_ONLY\"}")).andExpect(status().isBadRequest());

        sla("NOTIFY_ONLY", null);
        mvc.perform(as(get("/v1/admin/templates/" + KEY + "/sla"), "u_admin")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dueAfterHours").value(24));
        mvc.perform(as(delete("/v1/admin/templates/" + KEY + "/sla"), "u_admin")).andExpect(status().isNoContent());
        mvc.perform(as(get("/v1/admin/templates/" + KEY + "/sla"), "u_admin")).andExpect(status().isNotFound());
    }

    // ---- delegation ------------------------------------------------------------------------------------

    private String delegate(String from, String to, Instant ends) throws Exception {
        String body = mvc.perform(as(post("/v1/delegations"), from).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delegateId\":\"" + to + "\",\"endsAt\":\"" + ends + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void newTasksOfSomeoneOnDelegationGoToTheirDelegate() throws Exception {
        delegate("u_a", "u_deputy", t0.plus(Duration.ofDays(7)));

        String instance = start();

        assertThat(assignee(instance)).isEqualTo("u_deputy");
        assertThat(engineAssignees(instance)).containsExactly("USER:u_deputy");
        assertThat(audits("DELEGATED")).isEqualTo(1);
    }

    @Test
    void aRevokedOrExpiredDelegationNoLongerApplies() throws Exception {
        String id = delegate("u_a", "u_deputy", t0.plus(Duration.ofDays(7)));
        mvc.perform(as(delete("/v1/delegations/" + id), "u_a")).andExpect(status().isNoContent());
        assertThat(assignee(start())).isEqualTo("u_a");

        delegate("u_a", "u_deputy", t0.plus(Duration.ofDays(2)));
        clock.set(t0.plus(Duration.ofDays(3)));
        assertThat(assignee(start())).isEqualTo("u_a");
    }

    @Test
    void youCannotDelegateToYourselfOrRevokeSomeoneElsesDelegation() throws Exception {
        mvc.perform(as(post("/v1/delegations"), "u_a").contentType(MediaType.APPLICATION_JSON)
                .content("{\"delegateId\":\"u_a\",\"endsAt\":\"" + t0.plus(Duration.ofDays(1)) + "\"}"))
                .andExpect(status().isBadRequest());

        String id = delegate("u_a", "u_deputy", t0.plus(Duration.ofDays(7)));
        mvc.perform(as(delete("/v1/delegations/" + id), "u_other")).andExpect(status().is4xxClientError());
        mvc.perform(as(get("/v1/delegations"), "u_a")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(get("/v1/delegations"), "u_other")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theInitiatorCannotBeGivenTheirOwnRequestThroughDelegation() throws Exception {
        delegate("u_a", "u_1", t0.plus(Duration.ofDays(7)));

        assertThat(assignee(start())).isEqualTo("u_a");
    }

    @Test
    void delegationsAreIsolatedPerTenant() throws Exception {
        mvc.perform(post("/v1/delegations").header("X-Tenant-Id", "t_other").header("X-Actor-Id", "u_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delegateId\":\"u_deputy\",\"endsAt\":\"" + t0.plus(Duration.ofDays(7)) + "\"}"))
                .andExpect(status().isCreated());

        assertThat(assignee(start())).isEqualTo("u_a");
    }
}
