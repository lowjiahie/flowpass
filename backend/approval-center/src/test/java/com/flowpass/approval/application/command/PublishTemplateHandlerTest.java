package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.BpmnDefinitionSource;
import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import com.flowpass.approval.domain.definition.TemplateStatus;
import com.flowpass.approval.support.TestBpmn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Use-case tests for {@link PublishTemplateHandler}: versioned publishing and the expected business failures. */
@ExtendWith(MockitoExtension.class)
class PublishTemplateHandlerTest {

    private static final String KEY = "purchase_request";

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private BpmnDefinitionSource bpmnDefinitionSource;

    private final TenantId tenant = new TenantId("t_10086");
    private final ApproverRef publisher = new ApproverRef("USER", "u_admin");
    private final String bpmn = TestBpmn.purchaseRequest();

    private PublishTemplateHandler handler() {
        return new PublishTemplateHandler(templateRepository, bpmnDefinitionSource);
    }

    private PublishTemplateCommand command() {
        return new PublishTemplateCommand(tenant, KEY, publisher);
    }

    @Test
    void publishesTheDraftAsVersionOneAndRecordsThePublisher() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        template.saveDraft(bpmn);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        TemplateDTO dto = handler().handle(command());

        assertThat(dto.status()).isEqualTo("PUBLISHED");
        assertThat(dto.latestVersion()).isEqualTo(1);
        verify(templateRepository).saveDefinition(eq(template.id()), eq(1), eq(bpmn), anyString(), isNull(), eq(publisher));
        ArgumentCaptor<ApprovalTemplate> captor = ArgumentCaptor.forClass(ApprovalTemplate.class);
        verify(templateRepository).markPublished(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TemplateStatus.PUBLISHED);
        verify(bpmnDefinitionSource, never()).load(anyString());
    }

    @Test
    void theSnapshotIsWrittenBeforeTheVersionCounterIsBumped() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        template.saveDraft(bpmn);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        handler().handle(command());

        InOrder order = inOrder(templateRepository);
        order.verify(templateRepository).saveDefinition(any(), anyInt(), any(), any(), any(), any());
        order.verify(templateRepository).markPublished(any());
    }

    @Test
    void publishingAgainCreatesTheNextVersion() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        template.saveDraft(bpmn);
        template.publish(bpmn);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        TemplateDTO dto = handler().handle(command());

        assertThat(dto.latestVersion()).isEqualTo(2);
        verify(templateRepository).saveDefinition(eq(template.id()), eq(2), eq(bpmn), anyString(), isNull(), eq(publisher));
    }

    @Test
    void withoutADraftTheBuiltInDefinitionIsPublished() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));
        when(bpmnDefinitionSource.load(KEY)).thenReturn(bpmn);

        TemplateDTO dto = handler().handle(command());

        assertThat(dto.latestVersion()).isEqualTo(1);
        verify(templateRepository).saveDefinition(eq(template.id()), eq(1), eq(bpmn), anyString(), isNull(), eq(publisher));
    }

    @Test
    void withoutADraftOrABuiltInDefinitionItIsAValidationFailure() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, "no_such_process");
        when(templateRepository.findByKey(tenant, "no_such_process")).thenReturn(Optional.of(template));
        when(bpmnDefinitionSource.load("no_such_process")).thenThrow(new IllegalStateException("none"));

        assertThatThrownBy(() -> handler().handle(new PublishTemplateCommand(tenant, "no_such_process", publisher)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.VALIDATION_FAILED));
        verify(templateRepository, never()).saveDefinition(any(), anyInt(), any(), any(), any(), any());
        verify(templateRepository, never()).markPublished(any());
    }

    @Test
    void anInvalidDraftIsNeverSnapshotted() {
        // A draft can only be stored valid, but the definition is re-validated at publish time too.
        ApprovalTemplate template = ApprovalTemplate.hydrate("tpl_1", tenant, KEY, TemplateStatus.DRAFT,
                bpmn.replace("${hasApprovers}", "${a.b}"), null, 0);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> handler().handle(command()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.VALIDATION_FAILED));
        verify(templateRepository, never()).saveDefinition(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void aDisabledTemplateCannotBePublished() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        template.publish(bpmn);
        template.disable();
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> handler().handle(command()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.ILLEGAL_STATE));
        verify(templateRepository, never()).markPublished(any());
    }

    @Test
    void rejectsUnknownTemplate() {
        when(templateRepository.findByKey(tenant, "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler().handle(new PublishTemplateCommand(tenant, "missing", publisher)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(BusinessErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    void theDraftFormSchemaIsSnapshottedWithTheVersion() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        template.saveDraft(bpmn);
        template.saveFormSchema("{\"type\":\"object\"}");
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        handler().handle(command());

        verify(templateRepository).saveDefinition(eq(template.id()), eq(1), eq(bpmn), anyString(),
                eq("{\"type\":\"object\"}"), eq(publisher));
    }
}
