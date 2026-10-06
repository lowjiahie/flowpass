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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The production path: the default (non-{@code dev}) profile is a JWT resource server. The tenant comes
 * from the token's {@code tenant_id} claim, the actor from {@code sub}, and the permissions from the
 * token's authorities — never from headers or the request body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SecurityJwtHttpIntegrationTest {

    private static final String TENANT = "t_sec";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** No IdP in tests: the decoder is mocked, tokens are injected with the {@code jwt()} post-processor. */
    @MockBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RuntimeService runtimeService;

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
                VALUES ('rule-default', ?, 'purchase_request', 100, '[{"type":"USER","id":"u_2"}]'::jsonb)
                """, TENANT);
    }

    private RequestPostProcessor token(String subject, String tenant, String... authorities) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", tenant))
                .authorities(AuthorityUtils.createAuthorityList(authorities));
    }

    private String start(RequestPostProcessor token) throws Exception {
        MvcResult result = mvc.perform(post("/v1/approval-instances").with(token)
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"purchase_request\",\"externalBusinessKey\":\"biz-1\","
                                + "\"variables\":{\"amount\":100,\"currency\":\"CNY\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.initiatorId").value("kc-alice"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void requestWithoutATokenIs401WithAProblemBody() throws Exception {
        mvc.perform(get("/v1/approval-instances"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("approval.unauthenticated"));
    }

    @Test
    void identityHeadersAreIgnoredWithoutAToken() throws Exception {
        mvc.perform(get("/v1/approval-instances").header("X-Actor-Id", "u_1").header("X-Tenant-Id", TENANT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSubjectIsTheInitiatorAndTenantComesFromTheClaim() throws Exception {
        String instanceId = start(token("kc-alice", TENANT, "approval.request.create"));

        String owner = jdbc.queryForObject("SELECT tenant_id FROM approval_instance WHERE id = ?", String.class, instanceId);
        assertThat(owner).isEqualTo(TENANT);
    }

    @Test
    void onlyTheAssigneeTokenCanApproveAndOnlyWithTheApproveRole() throws Exception {
        start(token("kc-alice", TENANT, "approval.request.create"));
        String taskId = jdbc.queryForObject("SELECT id FROM approval_task", String.class);

        // Right role, wrong person.
        mvc.perform(post("/v1/tasks/" + taskId + "/approve")
                        .with(token("u_9", TENANT, "approval.request.approve"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("approval.forbidden"));

        // Right person, missing role.
        mvc.perform(post("/v1/tasks/" + taskId + "/approve")
                        .with(token("u_2", TENANT, "approval.request.read"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT status FROM approval_task WHERE id = ?", String.class, taskId))
                .isEqualTo("PENDING");

        // Right person, right role.
        mvc.perform(post("/v1/tasks/" + taskId + "/approve")
                        .with(token("u_2", TENANT, "approval.request.approve"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void aTokenOfAnotherTenantCannotReachTheTask() throws Exception {
        start(token("kc-alice", TENANT, "approval.request.create"));
        String taskId = jdbc.queryForObject("SELECT id FROM approval_task", String.class);

        mvc.perform(post("/v1/tasks/" + taskId + "/approve")
                        .with(token("u_2", "t_other", "approval.request.approve"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
