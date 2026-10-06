package com.flowpass.approval.integration;

import com.flowpass.approval.application.command.ApproveTaskCommand;
import com.flowpass.approval.application.command.ApproveTaskHandler;
import com.flowpass.approval.application.command.CreateTemplateCommand;
import com.flowpass.approval.application.command.CreateTemplateHandler;
import com.flowpass.approval.application.command.PublishTemplateCommand;
import com.flowpass.approval.application.command.PublishTemplateHandler;
import com.flowpass.approval.application.command.StartApprovalCommand;
import com.flowpass.approval.application.command.StartApprovalHandler;
import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.query.ListTemplatesHandler;
import com.flowpass.approval.application.query.ListTemplatesQuery;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end slice test for the template lifecycle (register → publish → start → approve) against a
 * real PostgreSQL.
 *
 * <p>Drives the real handlers through their MyBatis adapters and the classpath BPMN source: publishing
 * must snapshot the process definition and flip the template to {@code PUBLISHED}, after which an
 * instance can be started (pinning that definition version) and approved to completion. These are the
 * invariants that cannot be guarded by unit tests (schema, unique keys, publish status guard).</p>
 *
 * <p>Requires a local Docker daemon. Skips gracefully when Docker is unavailable via the JUnit
 * {@code @Testcontainers(disabledWithoutDocker = true)} extension flag.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@WithAllApprovalAuthorities
@Testcontainers(disabledWithoutDocker = true)
class TemplatePublishFlowIntegrationTest {

    private static final ApproverRef PUBLISHER = new ApproverRef("USER", "u_admin");
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
    private CreateTemplateHandler createHandler;

    @Autowired
    private PublishTemplateHandler publishHandler;

    @Autowired
    private ListTemplatesHandler listHandler;

    @Autowired
    private StartApprovalHandler startHandler;

    @Autowired
    private ApproveTaskHandler approveHandler;

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
    }

    @Test
    void createsPublishesThenStartsAndApproves() {
        // 1. Register the template -> DRAFT, no definition snapshot yet.
        TemplateDTO draft = createHandler.handle(new CreateTemplateCommand(new TenantId(TENANT), TEMPLATE_KEY));
        assertThat(draft.status()).isEqualTo("DRAFT");
        Integer defsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM process_definition WHERE template_id = ?", Integer.class, draft.id());
        assertThat(defsBefore).isZero();

        // 2. Publish -> PUBLISHED, and the classpath BPMN is snapshotted as version 1 with a checksum.
        TemplateDTO published = publishHandler.handle(
                new PublishTemplateCommand(new TenantId(TENANT), TEMPLATE_KEY, PUBLISHER));
        assertThat(published.status()).isEqualTo("PUBLISHED");

        String templateStatus = jdbc.queryForObject(
                "SELECT status FROM approval_template WHERE id = ?", String.class, draft.id());
        assertThat(templateStatus).isEqualTo("PUBLISHED");
        Integer version = jdbc.queryForObject(
                "SELECT version FROM process_definition WHERE template_id = ?", Integer.class, draft.id());
        assertThat(version).isEqualTo(1);
        String bpmnXml = jdbc.queryForObject(
                "SELECT bpmn_xml FROM process_definition WHERE template_id = ?", String.class, draft.id());
        assertThat(bpmnXml).contains("purchase_request");
        String checksum = jdbc.queryForObject(
                "SELECT checksum FROM process_definition WHERE template_id = ?", String.class, draft.id());
        assertThat(checksum).isNotBlank();

        // 3. The admin list surfaces the published template.
        List<TemplateDTO> templates = listHandler.handle(new ListTemplatesQuery(new TenantId(TENANT)));
        assertThat(templates).extracting(TemplateDTO::templateKey).containsExactly(TEMPLATE_KEY);
        assertThat(templates.get(0).status()).isEqualTo("PUBLISHED");

        // 4. Start an instance against the published template; it pins definition version 1.
        // Approvers come from the approval matrix (decision context), so seed a default rule first.
        jdbc.update("""
                INSERT INTO decision_rule (id, tenant_id, template_key, priority, approvers)
                VALUES ('rule-default', ?, 'purchase_request', 100, '[{"type":"USER","id":"u_2"}]'::jsonb)
                """, TENANT);
        StartApprovalCommand start = new StartApprovalCommand(
                new TenantId(TENANT),
                "app-default",
                TEMPLATE_KEY,
                "biz-publish-1",
                "idem-publish-1",
                new ApproverRef("USER", "u_1"),
                null,
                Map.of(),
                null);
        ApprovalInstanceDTO instance = startHandler.handle(start);
        assertThat(instance.status()).isEqualTo("RUNNING");
        assertThat(instance.definitionVersion()).isEqualTo(1);

        // 5. Approve the single task -> the instance completes.
        String taskId = jdbc.queryForObject(
                "SELECT id FROM approval_task WHERE instance_id = ?", String.class, instance.id());
        ApprovalInstanceDTO approved = approveHandler.handle(
                new ApproveTaskCommand(new TenantId(TENANT), taskId, new ApproverRef("USER", "u_2"), null));
        assertThat(approved.status()).isEqualTo("APPROVED");
    }

    @Test
    void publishingAgainCreatesTheNextImmutableVersion() {
        createHandler.handle(new CreateTemplateCommand(new TenantId(TENANT), TEMPLATE_KEY));
        publishHandler.handle(new PublishTemplateCommand(new TenantId(TENANT), TEMPLATE_KEY, PUBLISHER));

        TemplateDTO second = publishHandler.handle(
                new PublishTemplateCommand(new TenantId(TENANT), TEMPLATE_KEY, PUBLISHER));

        assertThat(second.latestVersion()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT version FROM process_definition ORDER BY version", Integer.class))
                .containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT latest_version FROM approval_template", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT DISTINCT published_by_id FROM process_definition", String.class))
                .containsExactly("u_admin");
    }

    @Test
    void rejectsPublishingWhenNoBpmnDefinitionExists() {
        createHandler.handle(new CreateTemplateCommand(new TenantId(TENANT), "no_such_process"));

        assertThatThrownBy(() -> publishHandler.handle(
                new PublishTemplateCommand(new TenantId(TENANT), "no_such_process", PUBLISHER)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.VALIDATION_FAILED));

        // The failed publish left the template untouched in DRAFT with no snapshot.
        String status = jdbc.queryForObject(
                "SELECT status FROM approval_template WHERE tenant_id = ? AND template_key = ?",
                String.class, TENANT, "no_such_process");
        assertThat(status).isEqualTo("DRAFT");
        Integer defs = jdbc.queryForObject("SELECT count(*) FROM process_definition", Integer.class);
        assertThat(defs).isZero();
    }

    @Test
    void rejectsDuplicateTemplateKey() {
        createHandler.handle(new CreateTemplateCommand(new TenantId(TENANT), TEMPLATE_KEY));

        assertThatThrownBy(() -> createHandler.handle(
                new CreateTemplateCommand(new TenantId(TENANT), TEMPLATE_KEY)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }
}
