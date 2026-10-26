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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rule conditions end to end: rules written with the condition language choose the approvers; an invalid or unsafe
 * condition is refused when the rule is defined; and cross-field business checks in a form schema ("x-rules")
 * refuse an application with the message the template author wrote.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class RuleConditionIntegrationTest {

    private static final String TENANT = "t_cond";
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
        TestBpmn.seedPublished(jdbc, TENANT, KEY, 1);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String actor) {
        return request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", actor);
    }

    private MvcResult defineRule(int priority, String expression, String approverId) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("templateKey", KEY);
        body.put("priority", priority);
        if (expression != null) {
            body.put("expression", expression);
        }
        body.put("approvers", List.of(Map.of("type", "USER", "id", approverId)));
        return mvc.perform(as(post("/v1/admin/decision-rules"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    private void defineRuleOk(int priority, String expression, String approverId) throws Exception {
        assertThat(defineRule(priority, expression, approverId).getResponse().getStatus()).isEqualTo(201);
    }

    private List<String> approversFor(String variablesJson) throws Exception {
        String bizKey = "biz-" + (++sequence);
        MvcResult result = mvc.perform(as(post("/v1/approval-instances"), "u_1")
                        .header("Idempotency-Key", "idem-" + bizKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"externalBusinessKey\":\"" + bizKey
                                + "\",\"variables\":" + variablesJson + "}"))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return jdbc.queryForList("SELECT assignee_id FROM approval_task WHERE instance_id = ?", String.class, id);
    }

    // ---- choosing approvers with conditions ----------------------------------------------------------

    @Test
    void conditionsInPriorityOrderChooseTheApprovers() throws Exception {
        defineRuleOk(10, "amount >= 10000 || (urgent && region in ['EU', 'UK'])", "u_cfo");
        defineRuleOk(20, "department in ['IT', 'HR'] && amount > 1000", "u_dept_head");
        defineRuleOk(100, null, "u_default");

        // Big amount.
        assertThat(approversFor("{\"amount\":20000,\"currency\":\"MYR\",\"department\":\"FIN\"}")).containsExactly("u_cfo");
        // Urgent request from the EU, however small.
        assertThat(approversFor("{\"amount\":50,\"currency\":\"MYR\",\"urgent\":true,\"region\":\"EU\"}"))
                .containsExactly("u_cfo");
        // Same region but not urgent: falls through.
        assertThat(approversFor("{\"amount\":50,\"currency\":\"MYR\",\"urgent\":false,\"region\":\"EU\"}"))
                .containsExactly("u_default");
        // IT over 1000 -> department head; IT at 1000 or less -> default.
        assertThat(approversFor("{\"amount\":2000,\"currency\":\"MYR\",\"department\":\"IT\"}")).containsExactly("u_dept_head");
        assertThat(approversFor("{\"amount\":1000,\"currency\":\"MYR\",\"department\":\"IT\"}")).containsExactly("u_default");
        // Missing fields never match an ordering.
        assertThat(approversFor("{\"note\":\"nothing relevant\"}")).containsExactly("u_default");
    }

    @Test
    void anExpressionCombinesWithTheAmountBandAndFieldMatches() throws Exception {
        mvc.perform(as(post("/v1/admin/decision-rules"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"priority\":10,\"amountMin\":1000,"
                                + "\"fieldMatches\":{\"department\":\"IT\"},\"expression\":\"urgent\","
                                + "\"approvers\":[{\"type\":\"USER\",\"id\":\"u_all_three\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expression").value("urgent"));
        defineRuleOk(100, null, "u_default");

        assertThat(approversFor("{\"amount\":2000,\"currency\":\"MYR\",\"department\":\"IT\",\"urgent\":true}"))
                .containsExactly("u_all_three");
        assertThat(approversFor("{\"amount\":2000,\"currency\":\"MYR\",\"department\":\"IT\",\"urgent\":false}"))
                .containsExactly("u_default");
        assertThat(approversFor("{\"amount\":500,\"currency\":\"MYR\",\"department\":\"IT\",\"urgent\":true}"))
                .containsExactly("u_default");
        assertThat(approversFor("{\"amount\":2000,\"currency\":\"MYR\",\"department\":\"HR\",\"urgent\":true}"))
                .containsExactly("u_default");
    }

    @Test
    void theRuleListShowsTheExpression() throws Exception {
        defineRuleOk(10, "amount > 1000 && region in ['EU']", "u_cfo");

        mvc.perform(as(get("/v1/admin/decision-rules?templateKey=" + KEY), "u_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].expression").value("amount > 1000 && region in ['EU']"));
    }

    @Test
    void thePreviewEvaluatesConditionsTheSameWayAsAStart() throws Exception {
        defineRuleOk(10, "amount >= 10000", "u_cfo");
        defineRuleOk(100, null, "u_default");

        mvc.perform(as(post("/v1/admin/decision-rules/preview"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"variables\":{\"amount\":15000}}"))
                .andExpect(jsonPath("$.approvers[0].id").value("u_cfo"));
        mvc.perform(as(post("/v1/admin/decision-rules/preview"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"variables\":{\"amount\":15}}"))
                .andExpect(jsonPath("$.approvers[0].id").value("u_default"));
    }

    @Test
    void anAmountSentAsTextIsRefusedSoNoRuleSeesADifferentValue() throws Exception {
        mvc.perform(as(post("/v1/approval-instances"), "u_1").header("Idempotency-Key", "idem-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"externalBusinessKey\":\"biz-text\","
                                + "\"variables\":{\"amount\":\"20000\",\"currency\":\"MYR\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("not text")));
    }

    // ---- refused conditions --------------------------------------------------------------------------

    @Test
    void anInvalidConditionIsRefusedWithTheReasonAndNoRuleIsStored() throws Exception {
        MvcResult result = defineRule(10, "amount = 5", "u_x");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("approval.validation_failed");
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.detail")).contains("use ==");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM decision_rule", Integer.class)).isZero();
    }

    @Test
    void aConditionThatCallsAMethodOrAFunctionIsRefused() throws Exception {
        for (String evil : List.of("runtimeService.deleteProcessInstance('a','b')", "execution.getVariable('x') == 1",
                "T(java.lang.Runtime).getRuntime().exec('calc')", "size(tags) > 1")) {
            assertThat(defineRule(10, evil, "u_x").getResponse().getStatus()).as(evil).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM decision_rule", Integer.class)).isZero();
    }

    @Test
    void anOverlongConditionIsRefused() throws Exception {
        assertThat(defineRule(10, "amount > 1 " + "|| amount > 2 ".repeat(60), "u_x").getResponse().getStatus())
                .isEqualTo(400);
    }

    // ---- business rules in the form ------------------------------------------------------------------

    private static final String LEAVE_SCHEMA = """
            {
              "type": "object",
              "required": ["startDate", "endDate"],
              "properties": {
                "startDate": {"type": "string", "format": "date"},
                "endDate":   {"type": "string", "format": "date"},
                "budget":    {"type": "number"},
                "total":     {"type": "number"}
              },
              "x-rules": [
                {"expression": "endDate > startDate", "message": "The end date must be after the start date",
                 "field": "endDate"},
                {"expression": "total == null || budget == null || total <= budget * 1.1",
                 "message": "The total may exceed the budget by at most 10%"}
              ]
            }
            """;

    private void publishWithBusinessRules() throws Exception {
        jdbc.update("DELETE FROM process_definition");
        jdbc.update("DELETE FROM approval_template");
        mvc.perform(as(post("/v1/admin/templates"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\"}")).andExpect(status().isCreated());
        mvc.perform(as(put("/v1/admin/templates/" + KEY + "/draft/form-schema"), "u_admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"formSchema\":" + LEAVE_SCHEMA + "}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/v1/admin/templates/" + KEY + "/publish"), "u_admin")).andExpect(status().isOk());
    }

    private MvcResult startWith(String variablesJson) throws Exception {
        String bizKey = "biz-" + (++sequence);
        return mvc.perform(as(post("/v1/approval-instances"), "u_1").header("Idempotency-Key", "idem-" + bizKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"" + KEY + "\",\"externalBusinessKey\":\"" + bizKey
                                + "\",\"variables\":" + variablesJson + "}"))
                .andReturn();
    }

    @Test
    void aFormBusinessRuleRefusesAnApplicationWithTheAuthorsMessage() throws Exception {
        publishWithBusinessRules();

        MvcResult bad = startWith("{\"startDate\":\"2026-03-10\",\"endDate\":\"2026-03-01\"}");

        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        String body = bad.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.errors[0].field")).isEqualTo("endDate");
        assertThat((String) JsonPath.read(body, "$.errors[0].message")).isEqualTo("The end date must be after the start date");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_instance", Integer.class)).isZero();
    }

    @Test
    void aFormBusinessRuleThatHoldsLetsTheApplicationThrough() throws Exception {
        publishWithBusinessRules();

        assertThat(startWith("{\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-10\",\"budget\":100,\"total\":105}")
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void severalBusinessRulesAreReportedTogether() throws Exception {
        publishWithBusinessRules();

        MvcResult bad = startWith("{\"startDate\":\"2026-03-10\",\"endDate\":\"2026-03-01\",\"budget\":100,\"total\":500}");

        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat((Integer) JsonPath.read(bad.getResponse().getContentAsString(), "$.errors.length()")).isEqualTo(2);
    }

    @Test
    void aBadBusinessRuleInTheSchemaIsRefusedWhenItIsSaved() throws Exception {
        jdbc.update("DELETE FROM process_definition");
        jdbc.update("DELETE FROM approval_template");
        mvc.perform(as(post("/v1/admin/templates"), "u_admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateKey\":\"" + KEY + "\"}")).andExpect(status().isCreated());

        mvc.perform(as(put("/v1/admin/templates/" + KEY + "/draft/form-schema"), "u_admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formSchema\":{\"type\":\"object\",\"x-rules\":[{\"expression\":\"a.b()\",\"message\":\"m\"}]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("x-rules")));
    }
}
