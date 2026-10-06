package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.ApproverResolution;
import com.flowpass.approval.application.common.FieldViolation;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.FormValidationPort;
import com.flowpass.approval.application.port.CandidateResolverPort;
import com.flowpass.approval.application.port.IdempotencyPort;
import com.flowpass.approval.application.port.OutboxPublisherPort;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowOutcome;
import com.flowpass.approval.application.port.WorkflowStep;
import com.flowpass.approval.application.port.WorkflowTask;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.DefinitionRepository;
import com.flowpass.approval.domain.definition.PublishedDefinition;
import com.flowpass.approval.domain.event.ApprovalInstanceCreated;
import com.flowpass.approval.domain.event.DomainEvent;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.runtime.ApprovalStatus;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import com.flowpass.approval.domain.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Use-case tests for {@link StartApprovalHandler} — happy path plus the two expected business
 * failures (unpublished template, duplicate idempotency key). Ports are mocked; no infrastructure.
 */
@ExtendWith(MockitoExtension.class)
class StartApprovalHandlerTest {

    @Mock
    private IdempotencyPort idempotencyPort;
    @Mock
    private DefinitionRepository definitionRepository;
    @Mock
    private ApprovalInstanceRepository instanceRepository;
    @Mock
    private OutboxPublisherPort outboxPublisher;
    @Mock
    private WorkflowEnginePort workflowEnginePort;
    @Mock
    private CandidateResolverPort candidateResolver;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private AuditRecordRepository auditRecordRepository;
    @Mock
    private FormValidationPort formValidation;

    private final TenantId tenant = new TenantId("t_10086");
    private final ApproverRef initiator = new ApproverRef("USER", "u_1");

    private StartApprovalCommand command() {
        return new StartApprovalCommand(
                tenant,
                "app_procurement",
                "purchase_request",
                "PO-2026-0001",
                "idem-1",
                initiator,
                null,
                Map.of("amount", BigDecimal.valueOf(23500.00), "currency", "MYR"),
                Map.of("source", "procurement"));
    }

    private PublishedDefinition definition() {
        return new PublishedDefinition("tpl_1", "purchase_request", 3, 2, "abc123", "PUBLISHED");
    }

    private StartApprovalHandler handler() {
        return new StartApprovalHandler(idempotencyPort, definitionRepository, instanceRepository,
                outboxPublisher, workflowEnginePort, candidateResolver, new WorkflowTaskMaterializer(taskRepository),
                auditRecordRepository, formValidation);
    }

    @Test
    void createsRunningInstanceAndPublishesCreatedEvent() {
        when(definitionRepository.findPublished(tenant, "purchase_request"))
                .thenReturn(Optional.of(definition()));
        when(candidateResolver.resolve(eq(tenant), eq("purchase_request"), any(), anyMap()))
                .thenReturn(new ApproverResolution(List.of(new CandidateApprover("USER", "u_2")), List.of()));
        when(workflowEnginePort.start(eq(tenant), anyString(), eq("purchase_request"), eq(3), anyMap(), anyList()))
                .thenReturn(new WorkflowStep(List.of(
                        new WorkflowTask("wf_1", "approveTask", "Approve (first round)", "USER", "u_2")), null));

        ApprovalInstanceDTO dto = handler().handle(command());

        assertThat(dto.status()).isEqualTo(ApprovalStatus.RUNNING.name());
        assertThat(dto.definitionVersion()).isEqualTo(3);
        assertThat(dto.businessKey()).isEqualTo("PO-2026-0001");

        ArgumentCaptor<ApprovalInstance> instanceCaptor = ArgumentCaptor.forClass(ApprovalInstance.class);
        verify(instanceRepository).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().status()).isEqualTo(ApprovalStatus.RUNNING);
        assertThat(instanceCaptor.getValue().version()).isZero();
        assertThat(instanceCaptor.getValue().definitionVersion()).isEqualTo(3);
        assertThat(instanceCaptor.getValue().variables()).containsEntry("amount", BigDecimal.valueOf(23500.00));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> eventCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxPublisher).append(eq(tenant), anyString(), eq("ApprovalInstance"), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).hasSize(1);
        assertThat(eventCaptor.getValue().get(0)).isInstanceOf(ApprovalInstanceCreated.class);

        verify(workflowEnginePort).start(eq(tenant), anyString(), eq("purchase_request"), eq(3), anyMap(),
                eq(List.of(new CandidateApprover("USER", "u_2"))));

        ArgumentCaptor<ApprovalTask> taskCaptor = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().assignee().id()).isEqualTo("u_2");
        assertThat(taskCaptor.getValue().status()).isEqualTo(TaskStatus.PENDING);
        assertThat(taskCaptor.getValue().nodeKey()).isEqualTo("approveTask");
        assertThat(taskCaptor.getValue().workflowTaskRef()).isEqualTo("wf_1");

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.STARTED);
        assertThat(auditCaptor.getValue().tenantId()).isEqualTo(tenant);
        assertThat(auditCaptor.getValue().instanceId()).startsWith("appr_").doesNotContain("PO-2026-0001");
        assertThat(auditCaptor.getValue().actor()).isEqualTo(initiator);
    }

    private StartApprovalCommand commandAs(ApproverRef actor, ApproverRef onBehalfOf) {
        return new StartApprovalCommand(tenant, "app_procurement", "purchase_request", "PO-2026-0001",
                "idem-1", actor, onBehalfOf, Map.of("amount", BigDecimal.valueOf(10), "currency", "MYR"), null);
    }

    @Test
    void systemActorMayStartOnBehalfOfAUser() {
        when(definitionRepository.findPublished(tenant, "purchase_request"))
                .thenReturn(Optional.of(definition()));
        when(candidateResolver.resolve(eq(tenant), eq("purchase_request"), any(), anyMap()))
                .thenReturn(ApproverResolution.none());
        when(workflowEnginePort.start(any(), anyString(), anyString(), any(), anyMap(), anyList()))
                .thenReturn(new WorkflowStep(List.of(), null));

        ApprovalInstanceDTO dto = handler().handle(
                commandAs(new ApproverRef("SYSTEM", "svc-erp"), new ApproverRef("USER", "u_7")));

        assertThat(dto.initiatorType()).isEqualTo("USER");
        assertThat(dto.initiatorId()).isEqualTo("u_7");
    }

    @Test
    void userCanOnlyStartAsThemselves() {
        // Naming yourself as the initiator is fine; naming someone else is not.
        assertThatThrownBy(() -> handler().handle(commandAs(initiator, new ApproverRef("USER", "u_7"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.FORBIDDEN));

        // Rejected before any state is touched.
        verifyNoInteractions(idempotencyPort, instanceRepository, workflowEnginePort, taskRepository);
    }

    @Test
    void missingOnBehalfOfDefaultsToTheActor() {
        when(definitionRepository.findPublished(tenant, "purchase_request"))
                .thenReturn(Optional.of(definition()));
        when(candidateResolver.resolve(eq(tenant), eq("purchase_request"), any(), anyMap()))
                .thenReturn(ApproverResolution.none());
        when(workflowEnginePort.start(any(), anyString(), anyString(), any(), anyMap(), anyList()))
                .thenReturn(new WorkflowStep(List.of(), null));

        ApprovalInstanceDTO dto = handler().handle(commandAs(initiator, null));

        assertThat(dto.initiatorId()).isEqualTo("u_1");
    }

    @Test
    void rejectsWhenTemplateNotPublished() {
        when(definitionRepository.findPublished(tenant, "purchase_request")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler().handle(command()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    void rejectsDuplicateIdempotencyKey() {
        doThrow(new BusinessException(BusinessErrorCode.IDEMPOTENCY_CONFLICT, "dup"))
                .when(idempotencyPort).claim(eq(tenant), eq("app_procurement"), eq("idem-1"), isNull());

        assertThatThrownBy(() -> handler().handle(command()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void nobodyResolvedStartsTheEngineWithNoApproversAndOpensNoBusinessTask() {
        // No matching rule must not look like an approval: the engine parks the run on an unassigned task.
        when(definitionRepository.findPublished(tenant, "purchase_request"))
                .thenReturn(Optional.of(definition()));
        when(candidateResolver.resolve(eq(tenant), eq("purchase_request"), any(), anyMap()))
                .thenReturn(ApproverResolution.none());
        when(workflowEnginePort.start(eq(tenant), anyString(), eq("purchase_request"), eq(3), anyMap(), eq(List.of())))
                .thenReturn(new WorkflowStep(List.of(
                        new WorkflowTask("wf_manual", "manualReview", "Manual review", null, null)), null));

        ApprovalInstanceDTO dto = handler().handle(command());

        assertThat(dto.status()).isEqualTo(ApprovalStatus.RUNNING.name());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void instanceIdIsUniqueEvenForTheSameBusinessKey() {
        when(definitionRepository.findPublished(tenant, "purchase_request"))
                .thenReturn(Optional.of(definition()));
        when(candidateResolver.resolve(eq(tenant), eq("purchase_request"), any(), anyMap()))
                .thenReturn(ApproverResolution.none());
        when(workflowEnginePort.start(any(), anyString(), anyString(), any(), anyMap(), anyList()))
                .thenReturn(new WorkflowStep(List.of(), null));

        String first = handler().handle(commandAs(initiator, null)).id();
        String second = handler().handle(commandAs(initiator, null)).id();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aFailedResolutionStartsTheEngineWithNoApproversAndRecordsWhy() {
        // e.g. the rule says "initiator's manager" but none is on record: never proceed with fewer approvers.
        when(definitionRepository.findPublished(tenant, "purchase_request"))
                .thenReturn(Optional.of(definition()));
        when(candidateResolver.resolve(eq(tenant), eq("purchase_request"), any(), anyMap()))
                .thenReturn(new ApproverResolution(List.of(), List.of("no manager recorded for u_1")));
        when(workflowEnginePort.start(eq(tenant), anyString(), eq("purchase_request"), eq(3), anyMap(), eq(List.of())))
                .thenReturn(new WorkflowStep(List.of(
                        new WorkflowTask("wf_manual", "manualReview", "Manual review", null, null)), null));

        ApprovalInstanceDTO dto = handler().handle(command());

        assertThat(dto.status()).isEqualTo(ApprovalStatus.RUNNING.name());
        verify(taskRepository, never()).save(any());
        ArgumentCaptor<AuditRecord> audit = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository).append(audit.capture());
        assertThat(audit.getValue().comment()).contains("manual review").contains("no manager recorded for u_1");
    }

    @Test
    void theInitiatorIsPassedToResolutionSoRelativeApproversCanBeResolved() {
        when(definitionRepository.findPublished(tenant, "purchase_request"))
                .thenReturn(Optional.of(definition()));
        when(candidateResolver.resolve(eq(tenant), eq("purchase_request"), eq(initiator), anyMap()))
                .thenReturn(ApproverResolution.none());
        when(workflowEnginePort.start(any(), anyString(), anyString(), any(), anyMap(), anyList()))
                .thenReturn(new WorkflowStep(List.of(), null));

        handler().handle(commandAs(initiator, null));

        verify(candidateResolver).resolve(eq(tenant), eq("purchase_request"), eq(initiator), anyMap());
    }

    // ---- input validation ------------------------------------------------------------------------------

    private StartApprovalCommand withVariables(Map<String, Object> variables) {
        return new StartApprovalCommand(tenant, "app_procurement", "purchase_request", "PO-2026-0001", "idem-1",
                initiator, null, variables, null);
    }

    private static void assertInvalid(Throwable ex, String... fields) {
        assertThat(ex).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.code()).isEqualTo(BusinessErrorCode.VALIDATION_FAILED);
            assertThat(e.errors()).extracting(FieldViolation::field).containsExactlyInAnyOrder(fields);
        });
    }

    private void publishedWithoutSchema() {
        when(definitionRepository.findPublished(tenant, "purchase_request")).thenReturn(Optional.of(definition()));
        when(definitionRepository.findFormSchema(tenant, "purchase_request", 3)).thenReturn(Optional.empty());
    }

    private void nothingWasCreated() {
        verifyNoInteractions(instanceRepository, workflowEnginePort, taskRepository, auditRecordRepository);
    }

    @Test
    void anInputThatBreaksTheTemplatesSchemaIsRefusedWithEveryFieldAndNothingIsCreated() {
        when(definitionRepository.findPublished(tenant, "purchase_request")).thenReturn(Optional.of(definition()));
        when(definitionRepository.findFormSchema(tenant, "purchase_request", 3)).thenReturn(Optional.of("{schema}"));
        when(formValidation.validate(eq("{schema}"), anyMap())).thenReturn(List.of(
                new FieldViolation("reason", "required property 'reason' not found"),
                new FieldViolation("items[0].qty", "must be at least 1")));

        assertThatThrownBy(() -> handler().handle(withVariables(Map.of("amount", 10, "currency", "MYR"))))
                .satisfies(ex -> assertInvalid(ex, "reason", "items[0].qty"))
                .hasMessageContaining("2 problems").hasMessageContaining("reason");
        nothingWasCreated();
    }

    @Test
    void theSchemaOfThePinnedVersionIsTheOneUsed() {
        when(definitionRepository.findPublished(tenant, "purchase_request")).thenReturn(Optional.of(definition()));
        when(definitionRepository.findFormSchema(tenant, "purchase_request", 3)).thenReturn(Optional.of("{v3}"));
        when(formValidation.validate(eq("{v3}"), anyMap())).thenReturn(List.of());
        when(candidateResolver.resolve(any(), anyString(), any(), anyMap())).thenReturn(ApproverResolution.none());
        when(workflowEnginePort.start(any(), anyString(), anyString(), any(), anyMap(), anyList()))
                .thenReturn(new WorkflowStep(List.of(), null));

        handler().handle(command());

        verify(definitionRepository).findFormSchema(tenant, "purchase_request", 3);
        verify(formValidation).validate(eq("{v3}"), anyMap());
    }

    @Test
    void aTemplateWithoutASchemaStillGetsThePlatformAmountChecks() {
        publishedWithoutSchema();

        assertThatThrownBy(() -> handler().handle(withVariables(Map.of("amount", "abc", "currency", "MYR"))))
                .satisfies(ex -> assertInvalid(ex, "amount"))
                .hasMessageContaining("must be a number");
        nothingWasCreated();
        verifyNoInteractions(formValidation);
    }

    @Test
    void negativeAmountsAndTooManyDecimalsAreRefused() {
        publishedWithoutSchema();

        assertThatThrownBy(() -> handler().handle(withVariables(Map.of("amount", -5, "currency", "MYR"))))
                .satisfies(ex -> assertInvalid(ex, "amount")).hasMessageContaining("negative");
        assertThatThrownBy(() -> handler().handle(withVariables(Map.of("amount", new BigDecimal("1.005"), "currency", "MYR"))))
                .satisfies(ex -> assertInvalid(ex, "amount")).hasMessageContaining("2 decimal");
    }

    @Test
    void anAmountNeedsACurrencyAndACurrencyNeedsToLookLikeOne() {
        publishedWithoutSchema();

        assertThatThrownBy(() -> handler().handle(withVariables(Map.of("amount", 10))))
                .satisfies(ex -> assertInvalid(ex, "currency"));
        assertThatThrownBy(() -> handler().handle(withVariables(Map.of("amount", 10, "currency", "ringgit"))))
                .satisfies(ex -> assertInvalid(ex, "currency"));
    }

    @Test
    void aBooleanIsNotAnAmount() {
        publishedWithoutSchema();

        assertThatThrownBy(() -> handler().handle(withVariables(Map.of("amount", true, "currency", "MYR"))))
                .satisfies(ex -> assertInvalid(ex, "amount"));
    }

    @Test
    void aFieldTheSchemaAlreadyReportedIsNotReportedAgainByThePlatformRules() {
        when(definitionRepository.findPublished(tenant, "purchase_request")).thenReturn(Optional.of(definition()));
        when(definitionRepository.findFormSchema(tenant, "purchase_request", 3)).thenReturn(Optional.of("{schema}"));
        when(formValidation.validate(eq("{schema}"), anyMap()))
                .thenReturn(List.of(new FieldViolation("amount", "string found, number expected")));

        assertThatThrownBy(() -> handler().handle(withVariables(Map.of("amount", "abc", "currency", "MYR"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errors())
                        .containsExactly(new FieldViolation("amount", "string found, number expected")));
    }

    @Test
    void numericStringsAndPlainNumbersAreAcceptedAsAmounts() {
        when(definitionRepository.findPublished(tenant, "purchase_request")).thenReturn(Optional.of(definition()));
        when(definitionRepository.findFormSchema(tenant, "purchase_request", 3)).thenReturn(Optional.empty());
        when(candidateResolver.resolve(any(), anyString(), any(), anyMap())).thenReturn(ApproverResolution.none());
        when(workflowEnginePort.start(any(), anyString(), anyString(), any(), anyMap(), anyList()))
                .thenReturn(new WorkflowStep(List.of(), null));

        ApprovalInstanceDTO dto = handler().handle(withVariables(Map.of("amount", "250.50", "currency", "MYR")));

        assertThat(dto.amount()).isEqualByComparingTo("250.50");
    }

    @Test
    void anInputWithNoAmountAtAllIsFine() {
        when(definitionRepository.findPublished(tenant, "purchase_request")).thenReturn(Optional.of(definition()));
        when(definitionRepository.findFormSchema(tenant, "purchase_request", 3)).thenReturn(Optional.empty());
        when(candidateResolver.resolve(any(), anyString(), any(), anyMap())).thenReturn(ApproverResolution.none());
        when(workflowEnginePort.start(any(), anyString(), anyString(), any(), anyMap(), anyList()))
                .thenReturn(new WorkflowStep(List.of(), null));

        assertThat(handler().handle(withVariables(Map.of("note", "hello"))).amount()).isNull();
    }
}
