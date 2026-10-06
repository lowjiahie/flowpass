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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The template lifecycle end to end through the HTTP API: draft → publish → new versions → running instances
 * keep their version → disable / enable, with the engine deploying exactly the stored definition.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class TemplateLifecycleIntegrationTest {

    private static final String TENANT = "t_tpl";
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
    void reset() {
        EngineTestSupport.clearRuns(runtimeService);
        jdbc.execute("""
                TRUNCATE approval_task, approval_instance, outbox_event, idempotency_record,
                         approval_workflow_ref, form_schema, process_definition, approval_template,
                         webhook_subscription, webhook_delivery, approval_action, decision_rule
                         RESTART IDENTITY CASCADE
                """);
        jdbc.update("""
                INSERT INTO decision_rule (id, tenant_id, template_key, priority, approvers)
                VALUES ('rule-default', ?, 'purchase_request', 100, '[{"type":"USER","id":"u_2"}]'::jsonb)
                """, TENANT);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String tenant, String actor) {
        return request.header("X-Tenant-Id", tenant).header("X-Actor-Id", actor);
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return as(request, TENANT, "u_admin");
    }

    private void createTemplate() throws Exception {
        mvc.perform(admin(post("/v1/admin/templates")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.latestVersion").value(0));
    }

    private void saveDraft(String bpmn) throws Exception {
        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bpmnXml", bpmn))))
                .andExpect(status().isOk());
    }

    private MvcResult publish() throws Exception {
        return mvc.perform(admin(post("/v1/admin/templates/" + KEY + "/publish"))).andReturn();
    }

    private void publishOk(int expectedVersion) throws Exception {
        mvc.perform(admin(post("/v1/admin/templates/" + KEY + "/publish")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.latestVersion").value(expectedVersion));
    }

    private MvcResult start() throws Exception {
        String bizKey = "biz-" + (++sequence);
        return mvc.perform(as(post("/v1/approval-instances"), TENANT, "u_1")
                        .header("Idempotency-Key", "idem-" + bizKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"externalBusinessKey\":\"" + bizKey
                                + "\",\"variables\":{\"amount\":100,\"currency\":\"CNY\"}}"))
                .andReturn();
    }

    private String taskNameOf(String instanceId) {
        return jdbc.queryForObject("SELECT name FROM approval_task WHERE instance_id = ?", String.class, instanceId);
    }

    // ---- versioning ----------------------------------------------------------------------------------

    @Test
    void everyPublishIsANewImmutableVersionWithItsPublisherAndDefinition() throws Exception {
        createTemplate();
        String v1 = TestBpmn.purchaseRequest();
        String v2 = v1.replace("Approve (first round)", "Approve (second version)");

        saveDraft(v1);
        publishOk(1);
        saveDraft(v2);
        publishOk(2);

        mvc.perform(admin(get("/v1/admin/templates/" + KEY + "/versions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[0].publishedById").value("u_admin"))
                .andExpect(jsonPath("$[0].publishedByType").value("USER"));

        // Version 1 still holds the definition that was published as version 1.
        String version1 = mvc.perform(admin(get("/v1/admin/templates/" + KEY + "/versions/1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(version1, "$.bpmnXml")).contains("Approve (first round)")
                .doesNotContain("second version");
        String version2 = mvc.perform(admin(get("/v1/admin/templates/" + KEY + "/versions/2")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(version2, "$.bpmnXml")).contains("Approve (second version)");

        mvc.perform(admin(get("/v1/admin/templates/" + KEY + "/versions/3"))).andExpect(status().isNotFound());
        mvc.perform(admin(get("/v1/admin/templates/" + KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value(2))
                .andExpect(jsonPath("$.draftBpmn").isNotEmpty());
    }

    @Test
    void aRunningInstanceKeepsItsVersionWhileNewInstancesGetTheNewOne() throws Exception {
        createTemplate();
        saveDraft(TestBpmn.purchaseRequest());
        publishOk(1);

        MvcResult first = start();
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        assertThat((Integer) JsonPath.read(first.getResponse().getContentAsString(), "$.definitionVersion")).isEqualTo(1);
        assertThat(taskNameOf(firstId)).isEqualTo("Approve (first round)");

        // A new version with a different task name goes live.
        saveDraft(TestBpmn.purchaseRequest().replace("Approve (first round)", "Approve (second version)"));
        publishOk(2);

        MvcResult second = start();
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.id");
        assertThat((Integer) JsonPath.read(second.getResponse().getContentAsString(), "$.definitionVersion")).isEqualTo(2);
        assertThat(taskNameOf(secondId)).as("the new instance runs the new definition").isEqualTo("Approve (second version)");

        // The old instance is untouched by the new version: it still completes on its own definition.
        String firstTask = jdbc.queryForObject("SELECT id FROM approval_task WHERE instance_id = ?", String.class, firstId);
        mvc.perform(as(post("/v1/tasks/" + firstTask + "/approve"), TENANT, "u_2")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.definitionVersion").value(1));

        // Both versions were deployed to the engine as separate, tenant-scoped definitions.
        assertThat(jdbc.queryForList(
                "SELECT NAME_ FROM ACT_RE_DEPLOYMENT WHERE TENANT_ID_ = ? AND NAME_ LIKE 'purchase_request_v%' ORDER BY NAME_",
                String.class, TENANT)).containsExactly("purchase_request_v1", "purchase_request_v2");
    }

    @Test
    void withoutADraftTheBuiltInDefinitionIsPublished() throws Exception {
        createTemplate();

        publishOk(1);

        String stored = jdbc.queryForObject("SELECT bpmn_xml FROM process_definition", String.class);
        assertThat(stored).isEqualTo(TestBpmn.purchaseRequest());
        assertThat(start().getResponse().getStatus()).isEqualTo(201);
    }

    // ---- hostile drafts ------------------------------------------------------------------------------

    @Test
    void aDraftThatCouldExecuteCodeIsRefusedAndNothingIsStored() throws Exception {
        createTemplate();
        String evil = TestBpmn.purchaseRequest().replace("<endEvent id=\"endApproved\"",
                "<scriptTask id=\"pwn\" scriptFormat=\"groovy\"><script>Runtime.getRuntime().exec('calc')</script></scriptTask>"
                        + "<endEvent id=\"endApproved\"");

        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bpmnXml", evil))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.validation_failed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("scriptTask")));

        mvc.perform(admin(get("/v1/admin/templates/" + KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftBpmn").doesNotExist());
        // It cannot be published either: with no draft, the built-in definition is what publishes.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM process_definition", Integer.class)).isZero();
    }

    @Test
    void anExpressionWithAMethodCallIsRefused() throws Exception {
        createTemplate();
        String evil = TestBpmn.purchaseRequest().replace("${hasApprovers}", "${runtimeService.deleteProcessInstance('a','b')}");

        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bpmnXml", evil))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.validation_failed"));
    }

    @Test
    void aProcessIdThatIsNotTheTemplateKeyIsRefused() throws Exception {
        createTemplate();

        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bpmnXml", TestBpmn.forKey("something_else")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("template key")));
    }

    // ---- disable / enable ----------------------------------------------------------------------------

    @Test
    void aDisabledTemplateStartsNothingNewButRunningInstancesFinishAndEnableResumes() throws Exception {
        createTemplate();
        publishOk(1);
        String instanceId = JsonPath.read(start().getResponse().getContentAsString(), "$.id");

        mvc.perform(admin(post("/v1/admin/templates/" + KEY + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.latestVersion").value(1));

        assertThat(start().getResponse().getStatus()).as("no new instances while disabled").isEqualTo(404);
        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bpmnXml", TestBpmn.purchaseRequest()))))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(post("/v1/admin/templates/" + KEY + "/publish"))).andExpect(status().isBadRequest());

        String task = jdbc.queryForObject("SELECT id FROM approval_task WHERE instance_id = ?", String.class, instanceId);
        mvc.perform(as(post("/v1/tasks/" + task + "/approve"), TENANT, "u_2")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(admin(post("/v1/admin/templates/" + KEY + "/enable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        assertThat(start().getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void enablingAPublishedTemplateAndDisablingADraftAreRefused() throws Exception {
        createTemplate();
        mvc.perform(admin(post("/v1/admin/templates/" + KEY + "/disable"))).andExpect(status().isBadRequest());
        publishOk(1);
        mvc.perform(admin(post("/v1/admin/templates/" + KEY + "/enable"))).andExpect(status().isBadRequest());
    }

    // ---- isolation, authorization, concurrency ---------------------------------------------------------

    @Test
    void anotherTenantCannotSeeOrChangeTheTemplate() throws Exception {
        createTemplate();
        publishOk(1);

        for (MockHttpServletRequestBuilder request : List.of(
                get("/v1/admin/templates/" + KEY),
                get("/v1/admin/templates/" + KEY + "/versions"),
                get("/v1/admin/templates/" + KEY + "/versions/1"),
                post("/v1/admin/templates/" + KEY + "/publish"),
                post("/v1/admin/templates/" + KEY + "/disable"))) {
            mvc.perform(as(request, "t_other", "u_admin")).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT status FROM approval_template", String.class)).isEqualTo("PUBLISHED");
    }

    @Test
    void lifecycleEndpointsNeedTheTemplateManageAuthority() throws Exception {
        createTemplate();
        String readOnly = "approval.request.read";

        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft")).header("X-Actor-Authorities", readOnly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bpmnXml", TestBpmn.purchaseRequest()))))
                .andExpect(status().isForbidden());
        for (String path : List.of("/publish", "/disable", "/enable")) {
            mvc.perform(admin(post("/v1/admin/templates/" + KEY + path)).header("X-Actor-Authorities", readOnly))
                    .andExpect(status().isForbidden());
        }
        for (String path : List.of("", "/versions", "/versions/1")) {
            mvc.perform(admin(get("/v1/admin/templates/" + KEY + path)).header("X-Actor-Authorities", readOnly))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void concurrentPublishesNeverShareAVersionNumber() throws Exception {
        createTemplate();
        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        int ok = 0;
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return publish().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                int code = result.get();
                if (code == 200) {
                    ok++;
                } else {
                    assertThat(code).as("a losing publish is a conflict, never a server error").isEqualTo(409);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ok).isGreaterThanOrEqualTo(1);
        List<Integer> versions = jdbc.queryForList("SELECT version FROM process_definition ORDER BY version", Integer.class);
        assertThat(versions).doesNotHaveDuplicates().hasSize(ok);
        assertThat(jdbc.queryForObject("SELECT latest_version FROM approval_template", Integer.class)).isEqualTo(ok);
        assertThat(versions.get(versions.size() - 1)).isEqualTo(ok);
    }
}
