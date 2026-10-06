package com.flowpass.approval.integration;

import com.flowpass.approval.application.command.CreateDecisionRuleCommand;
import com.flowpass.approval.application.command.CreateDecisionRuleHandler;
import com.flowpass.approval.application.command.StartApprovalCommand;
import com.flowpass.approval.application.command.StartApprovalHandler;
import com.flowpass.approval.application.query.ListDecisionRulesHandler;
import com.flowpass.approval.application.query.ListDecisionRulesQuery;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.contract.dto.DecisionRuleDTO;
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

/**
 * End-to-end slice test for the decision/rules context against a real PostgreSQL.
 *
 * <p>Defines rules through {@link CreateDecisionRuleHandler} (exercising the JSONB round-trip of the
 * structured condition) and starts real instances through {@link StartApprovalHandler}: first-round tasks
 * must come from the highest-priority matching rule, fall back to a default rule, or be absent entirely
 * when nothing matches. These are the invariants the mocked unit tests cannot guard (schema, SQL ordering,
 * jsonb serialisation).</p>
 *
 * <p>Requires a local Docker daemon. Skips gracefully when Docker is unavailable via the JUnit
 * {@code @Testcontainers(disabledWithoutDocker = true)} extension flag.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@WithAllApprovalAuthorities
@Testcontainers(disabledWithoutDocker = true)
class DecisionRulesIntegrationTest {

    private static final String TENANT = "t_10086";
    private static final String TEMPLATE_KEY = "purchase_request";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CreateDecisionRuleHandler createRuleHandler;

    @Autowired
    private ListDecisionRulesHandler listRulesHandler;

    @Autowired
    private StartApprovalHandler startHandler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RuntimeService runtimeService;

    @BeforeEach
    void clean() {
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
                VALUES ('pd-1', 'tpl-1', 1, ?, 'chk-1')
                """, TestBpmn.purchaseRequest());
    }

    private void defineRule(int priority, BigDecimal min, BigDecimal max, Map<String, String> fieldMatches,
                            List<ApproverRef> approvers) {
        createRuleHandler.handle(new CreateDecisionRuleCommand(
                new TenantId(TENANT), TEMPLATE_KEY, priority, min, max, fieldMatches, approvers));
    }

    private ApprovalInstanceDTO start(String idemKey, Map<String, Object> variables) {
        return startHandler.handle(new StartApprovalCommand(
                new TenantId(TENANT),
                "app-default",
                TEMPLATE_KEY,
                "biz-" + idemKey,
                idemKey,
                new ApproverRef("USER", "u_1"),
                null,
                variables,
                null));
    }

    private List<String> assigneesOf(String instanceId) {
        return jdbc.queryForList(
                "SELECT assignee_id FROM approval_task WHERE tenant_id = ? AND instance_id = ? ORDER BY assignee_id",
                String.class, TENANT, instanceId);
    }

    @Test
    void appliesHighestPriorityMatchingRule() {
        // Lower number = higher priority. The banded rule must beat the default fallback.
        defineRule(10, new BigDecimal("1000.00"), null, null, List.of(new ApproverRef("USER", "u_big")));
        defineRule(100, null, null, null, List.of(new ApproverRef("USER", "u_default")));

        ApprovalInstanceDTO instance = start("idem-band", Map.of("amount", new BigDecimal("5000.00"), "currency", "CNY"));

        assertThat(assigneesOf(instance.id())).containsExactly("u_big");
    }

    @Test
    void fallsBackToDefaultRuleWhenNoBandMatches() {
        defineRule(10, new BigDecimal("1000.00"), null, null, List.of(new ApproverRef("USER", "u_big")));
        defineRule(100, null, null, null, List.of(new ApproverRef("USER", "u_default")));

        ApprovalInstanceDTO instance = start("idem-small", Map.of("amount", new BigDecimal("100.00"), "currency", "CNY"));

        assertThat(assigneesOf(instance.id())).containsExactly("u_default");
    }

    @Test
    void matchesOnStructuredFieldCondition() {
        defineRule(10, null, null, Map.of("currency", "USD"), List.of(new ApproverRef("USER", "u_usd")));
        defineRule(100, null, null, null, List.of(new ApproverRef("USER", "u_default")));

        ApprovalInstanceDTO usd = start("idem-usd", Map.of("amount", new BigDecimal("10.00"), "currency", "USD"));
        assertThat(assigneesOf(usd.id())).containsExactly("u_usd");

        ApprovalInstanceDTO cny = start("idem-cny", Map.of("amount", new BigDecimal("10.00"), "currency", "CNY"));
        assertThat(assigneesOf(cny.id())).containsExactly("u_default");
    }

    @Test
    void createsNoTasksWhenNoRuleMatches() {
        // Only a banded rule, and the instance amount falls outside it: no fallback, so manual handling.
        defineRule(10, new BigDecimal("1000.00"), null, null, List.of(new ApproverRef("USER", "u_big")));

        ApprovalInstanceDTO instance = start("idem-none", Map.of("amount", new BigDecimal("100.00"), "currency", "CNY"));

        assertThat(instance.status()).isEqualTo("RUNNING");
        assertThat(assigneesOf(instance.id())).isEmpty();
    }

    @Test
    void listsRulesByTemplateOrderedByPriority() {
        defineRule(100, null, null, null, List.of(new ApproverRef("USER", "u_default")));
        defineRule(10, new BigDecimal("1000.00"), null, null, List.of(new ApproverRef("USER", "u_big")));
        createRuleHandler.handle(new CreateDecisionRuleCommand(
                new TenantId(TENANT), "other_template", 10, null, null, null,
                List.of(new ApproverRef("USER", "u_other"))));

        List<DecisionRuleDTO> rules = listRulesHandler.handle(
                new ListDecisionRulesQuery(new TenantId(TENANT), TEMPLATE_KEY));

        assertThat(rules).extracting(DecisionRuleDTO::priority).containsExactly(10, 100);
        assertThat(rules).allSatisfy(rule -> assertThat(rule.templateKey()).isEqualTo(TEMPLATE_KEY));
        assertThat(rules.get(0).amountMin()).isEqualByComparingTo("1000.00");

        // Without a filter the admin listing surfaces every tenant rule, across templates.
        List<DecisionRuleDTO> all = listRulesHandler.handle(new ListDecisionRulesQuery(new TenantId(TENANT), null));
        assertThat(all).hasSize(3);
    }
}
