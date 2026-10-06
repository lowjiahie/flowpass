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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Input validation end to end: a template's form schema is published with its version, applications are checked
 * against the schema of the version they pin, every problem is reported at once, a refused application leaves
 * nothing behind, and the platform's amount rules protect templates that have no schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class FormValidationIntegrationTest {

    private static final String TENANT = "t_form";
    private static final String KEY = "purchase_request";

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["amount", "currency", "reason"],
              "additionalProperties": false,
              "properties": {
                "amount":   {"type": "number", "exclusiveMinimum": 0, "maximum": 1000},
                "currency": {"enum": ["MYR", "USD"]},
                "reason":   {"type": "string", "minLength": 5, "maxLength": 100},
                "email":    {"type": "string", "format": "email"},
                "needBy":   {"type": "string", "format": "date"},
                "urgent":   {"type": "boolean"},
                "items": {"type": "array", "minItems": 1,
                          "items": {"type": "object", "required": ["sku", "qty"],
                                    "properties": {"sku": {"type": "string"}, "qty": {"type": "integer", "minimum": 1}}}}
              }
            }
            """;

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

    private void createAndDraftTemplate() throws Exception {
        mvc.perform(admin(post("/v1/admin/templates")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bpmnXml", TestBpmn.purchaseRequest()))))
                .andExpect(status().isOk());
    }

    private void putSchema(String schemaJson) throws Exception {
        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft/form-schema")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formSchema\":" + schemaJson + "}"))
                .andExpect(status().isOk());
    }

    private void publishOk(int version) throws Exception {
        mvc.perform(admin(post("/v1/admin/templates/" + KEY + "/publish")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latestVersion").value(version));
    }

    private MvcResult start(String variablesJson, String idempotencyKey) throws Exception {
        String bizKey = "biz-" + (++sequence);
        return mvc.perform(as(post("/v1/approval-instances"), TENANT, "u_1")
                        .header("Idempotency-Key", idempotencyKey == null ? "idem-" + bizKey : idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"externalBusinessKey\":\"" + bizKey
                                + "\",\"variables\":" + variablesJson + "}"))
                .andReturn();
    }

    private static final String VALID = "{\"amount\":250.5,\"currency\":\"MYR\",\"reason\":\"Laptops for the team\"}";

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // ---- different data types ------------------------------------------------------------------------

    @Test
    void textNumbersBooleansDatesEmailsEnumsAndNestedListsAreAllAcceptedAndStored() throws Exception {
        createAndDraftTemplate();
        putSchema(SCHEMA);
        publishOk(1);

        MvcResult result = start("""
                {"amount":250.5,"currency":"MYR","reason":"Laptops for the team","email":"alice@example.com",
                 "needBy":"2026-12-31","urgent":true,
                 "items":[{"sku":"LAP-1","qty":3},{"sku":"MOUSE-9","qty":10}]}
                """, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        String stored = jdbc.queryForObject("SELECT variables_json::text FROM approval_instance WHERE id = ?", String.class, id);
        assertThat(stored).contains("alice@example.com").contains("LAP-1").contains("2026-12-31");
    }

    // ---- refused applications ------------------------------------------------------------------------

    @Test
    void everyProblemIsReportedAtOnceWithItsFieldAndNothingIsCreated() throws Exception {
        createAndDraftTemplate();
        putSchema(SCHEMA);
        publishOk(1);

        mvc.perform(as(post("/v1/approval-instances"), TENANT, "u_1").header("Idempotency-Key", "idem-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateKey":"purchase_request","externalBusinessKey":"biz-bad","variables":
                                 {"amount":5000,"currency":"EUR","email":"nope","items":[{"sku":"A","qty":0}]}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.validation_failed"))
                .andExpect(jsonPath("$.detail").value(containsString("problems")))
                .andExpect(jsonPath("$.errors[?(@.field=='amount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='currency')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='reason')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='items[0].qty')]").exists());

        assertThat(count("approval_instance")).isZero();
        assertThat(count("approval_task")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(count("approval_action")).isZero();
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceTenantId(TENANT).count()).isZero();
    }

    @Test
    void aRefusedApplicationDoesNotBurnItsIdempotencyKey() throws Exception {
        createAndDraftTemplate();
        putSchema(SCHEMA);
        publishOk(1);

        assertThat(start("{\"amount\":5000,\"currency\":\"MYR\",\"reason\":\"too much money\"}", "idem-retry")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(count("idempotency_record")).as("the claim rolled back with the refusal").isZero();

        // The caller fixes the data and retries with the same key.
        assertThat(start(VALID, "idem-retry").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void numbersSentAsTextAreRefusedByATypedSchema() throws Exception {
        createAndDraftTemplate();
        putSchema(SCHEMA);
        publishOk(1);

        mvc.perform(as(post("/v1/approval-instances"), TENANT, "u_1").header("Idempotency-Key", "idem-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"purchase_request\",\"externalBusinessKey\":\"biz-text\",\"variables\":"
                                + "{\"amount\":\"250\",\"currency\":\"MYR\",\"reason\":\"Laptops for the team\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    // ---- a template without a schema -------------------------------------------------------------------

    @Test
    void aTemplateWithoutASchemaStillRefusesABadAmountInsteadOfFailing() throws Exception {
        createAndDraftTemplate();
        publishOk(1);

        for (String bad : List.of("{\"amount\":\"abc\",\"currency\":\"MYR\"}", "{\"amount\":-1,\"currency\":\"MYR\"}",
                "{\"amount\":1.005,\"currency\":\"MYR\"}", "{\"amount\":10}", "{\"amount\":10,\"currency\":\"ringgit\"}")) {
            MvcResult result = start(bad, null);
            assertThat(result.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("approval.validation_failed");
        }
        assertThat(start("{\"amount\":10,\"currency\":\"MYR\",\"anything\":[1,2,{\"x\":true}]}", null)
                .getResponse().getStatus()).as("free-form fields are fine without a schema").isEqualTo(201);
    }

    // ---- schemas are versioned with the template ---------------------------------------------------------

    @Test
    void anApplicationIsCheckedAgainstTheSchemaOfTheVersionItPinsAndTheFormEndpointShowsTheLatest() throws Exception {
        createAndDraftTemplate();
        putSchema(SCHEMA);
        publishOk(1);
        // v1 allows up to 1000; the stricter v2 only up to 100.
        putSchema(SCHEMA.replace("\"maximum\": 1000", "\"maximum\": 100"));
        publishOk(2);

        MvcResult big = start("{\"amount\":500,\"currency\":\"MYR\",\"reason\":\"Laptops for the team\"}", null);
        assertThat(big.getResponse().getStatus()).as("500 is over the v2 limit").isEqualTo(400);

        mvc.perform(as(get("/v1/templates/" + KEY + "/form"), TENANT, "u_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.formSchema").value(containsString("\"maximum\":100")));

        mvc.perform(admin(get("/v1/admin/templates/" + KEY + "/versions/1")))
                .andExpect(jsonPath("$.formSchema").value(containsString("\"maximum\":1000")));
        mvc.perform(admin(get("/v1/admin/templates/" + KEY + "/versions/2")))
                .andExpect(jsonPath("$.formSchema").value(containsString("\"maximum\":100")));
        assertThat(jdbc.queryForList("SELECT version FROM form_schema ORDER BY version", Integer.class))
                .containsExactly(1, 2);
    }

    @Test
    void aRunningInstanceKeepsItsVersionWhenTheSchemaGetsStricter() throws Exception {
        createAndDraftTemplate();
        putSchema(SCHEMA);
        publishOk(1);
        MvcResult running = start("{\"amount\":500,\"currency\":\"MYR\",\"reason\":\"Laptops for the team\"}", null);
        assertThat(running.getResponse().getStatus()).isEqualTo(201);
        String id = JsonPath.read(running.getResponse().getContentAsString(), "$.id");

        putSchema(SCHEMA.replace("\"maximum\": 1000", "\"maximum\": 100"));
        publishOk(2);

        // The old application is untouched and still completes.
        String task = jdbc.queryForObject("SELECT id FROM approval_task WHERE instance_id = ?", String.class, id);
        mvc.perform(as(post("/v1/tasks/" + task + "/approve"), TENANT, "u_2")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void removingTheSchemaAndPublishingMakesTheNextVersionUnconstrained() throws Exception {
        createAndDraftTemplate();
        putSchema(SCHEMA);
        publishOk(1);

        mvc.perform(admin(delete("/v1/admin/templates/" + KEY + "/draft/form-schema"))).andExpect(status().isOk());
        publishOk(2);

        mvc.perform(as(get("/v1/templates/" + KEY + "/form"), TENANT, "u_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.formSchema").doesNotExist());
        assertThat(start("{\"anything\":\"goes\"}", null).getResponse().getStatus()).isEqualTo(201);
    }

    // ---- schemas are vetted --------------------------------------------------------------------------

    @Test
    void aSchemaWithARegularExpressionOrARemoteReferenceIsRefusedAndNotStored() throws Exception {
        createAndDraftTemplate();

        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft/form-schema")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"(a+)+$\"}}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.validation_failed"))
                .andExpect(jsonPath("$.detail").value(containsString("regular expressions")));
        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft/form-schema")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"https://evil.example/s.json\"}}}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft/form-schema")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"nonsense\"}}}}"))
                .andExpect(status().isBadRequest());

        mvc.perform(admin(get("/v1/admin/templates/" + KEY)))
                .andExpect(jsonPath("$.draftFormSchema").doesNotExist());
    }

    @Test
    void anEmptySchemaBodyIsRefused() throws Exception {
        createAndDraftTemplate();

        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft/form-schema")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formSchema\":{}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft/form-schema")).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theAdminDetailShowsTheDraftSchema() throws Exception {
        createAndDraftTemplate();
        putSchema(SCHEMA);

        mvc.perform(admin(get("/v1/admin/templates/" + KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftFormSchema").value(containsString("\"required\"")));
    }

    // ---- access, isolation ---------------------------------------------------------------------------

    @Test
    void formSchemaEditingNeedsTheTemplateManageAuthorityAndTheFormEndpointTheCreateAuthority() throws Exception {
        createAndDraftTemplate();
        publishOk(1);

        mvc.perform(admin(put("/v1/admin/templates/" + KEY + "/draft/form-schema")).header("X-Actor-Authorities", "approval.request.read")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"formSchema\":" + SCHEMA + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(admin(delete("/v1/admin/templates/" + KEY + "/draft/form-schema")).header("X-Actor-Authorities", "approval.request.read"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/v1/templates/" + KEY + "/form"), TENANT, "u_1").header("X-Actor-Authorities", "approval.request.read"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theFormOfAnUnpublishedOrDisabledTemplateIsNotAvailable() throws Exception {
        mvc.perform(as(get("/v1/templates/" + KEY + "/form"), TENANT, "u_1")).andExpect(status().isNotFound());

        createAndDraftTemplate();
        mvc.perform(as(get("/v1/templates/" + KEY + "/form"), TENANT, "u_1")).andExpect(status().isNotFound());

        publishOk(1);
        mvc.perform(admin(post("/v1/admin/templates/" + KEY + "/disable"))).andExpect(status().isOk());
        mvc.perform(as(get("/v1/templates/" + KEY + "/form"), TENANT, "u_1")).andExpect(status().isNotFound());
    }

    @Test
    void anotherTenantHasItsOwnSchemaAndCannotSeeThisOne() throws Exception {
        createAndDraftTemplate();
        putSchema(SCHEMA);
        publishOk(1);

        mvc.perform(as(get("/v1/templates/" + KEY + "/form"), "t_other", "u_1")).andExpect(status().isNotFound());
        mvc.perform(as(put("/v1/admin/templates/" + KEY + "/draft/form-schema"), "t_other", "u_admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"formSchema\":" + SCHEMA + "}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM form_schema", Integer.class)).isEqualTo(1);
    }
}
