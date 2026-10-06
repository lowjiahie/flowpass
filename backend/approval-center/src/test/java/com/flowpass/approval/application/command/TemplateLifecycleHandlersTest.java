package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.FormValidationPort;
import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import com.flowpass.approval.domain.definition.TemplateStatus;
import com.flowpass.approval.support.TestBpmn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Use-case tests for saving a draft and disabling / enabling a template. */
@ExtendWith(MockitoExtension.class)
class TemplateLifecycleHandlersTest {

    private static final String KEY = "purchase_request";

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private FormValidationPort formValidation;

    private final TenantId tenant = new TenantId("t_10086");
    private final String bpmn = TestBpmn.purchaseRequest();

    private static void assertCode(Throwable ex, BusinessErrorCode code) {
        assertThat(ex).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---- save draft ----------------------------------------------------------------------------------

    @Test
    void savesAValidDraft() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        TemplateDTO dto = new SaveTemplateDraftHandler(templateRepository)
                .handle(new SaveTemplateDraftCommand(tenant, KEY, bpmn));

        assertThat(dto.status()).isEqualTo("DRAFT");
        assertThat(template.draftBpmn()).isEqualTo(bpmn);
        verify(templateRepository).saveDraft(template);
    }

    @Test
    void aDraftThatBreaksTheFlowContractIsAValidationFailureAndNothingIsStored() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> new SaveTemplateDraftHandler(templateRepository)
                .handle(new SaveTemplateDraftCommand(tenant, KEY, bpmn.replace("<endEvent", "<scriptTask id=\"x\"/><endEvent"))))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("scriptTask");
        verify(templateRepository, never()).saveDraft(any());
    }

    @Test
    void aDisabledTemplateCannotBeEdited() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        template.publish(bpmn);
        template.disable();
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> new SaveTemplateDraftHandler(templateRepository)
                .handle(new SaveTemplateDraftCommand(tenant, KEY, bpmn)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE));
    }

    @Test
    void savingADraftForAnUnknownTemplateIsNotFound() {
        when(templateRepository.findByKey(tenant, "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new SaveTemplateDraftHandler(templateRepository)
                .handle(new SaveTemplateDraftCommand(tenant, "missing", bpmn)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.TEMPLATE_NOT_FOUND));
    }

    // ---- disable / enable ----------------------------------------------------------------------------

    @Test
    void disablesAPublishedTemplate() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        template.publish(bpmn);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        TemplateDTO dto = new SetTemplateEnabledHandler(templateRepository)
                .handle(new SetTemplateEnabledCommand(tenant, KEY, false));

        assertThat(dto.status()).isEqualTo("DISABLED");
        assertThat(dto.latestVersion()).isEqualTo(1);
        verify(templateRepository).updateStatus(template);
    }

    @Test
    void enablesADisabledTemplate() {
        ApprovalTemplate template = ApprovalTemplate.hydrate("tpl_1", tenant, KEY, TemplateStatus.DISABLED, null, null, 2);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        TemplateDTO dto = new SetTemplateEnabledHandler(templateRepository)
                .handle(new SetTemplateEnabledCommand(tenant, KEY, true));

        assertThat(dto.status()).isEqualTo("PUBLISHED");
        verify(templateRepository).updateStatus(template);
    }

    @Test
    void aWrongTransitionIsAnIllegalStateAndNothingIsPersisted() {
        ApprovalTemplate draft = ApprovalTemplate.start(tenant, KEY);
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> new SetTemplateEnabledHandler(templateRepository)
                .handle(new SetTemplateEnabledCommand(tenant, KEY, false)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE));
        verify(templateRepository, never()).updateStatus(any());
    }

    // ---- form schema ---------------------------------------------------------------------------------

    private SaveTemplateFormSchemaHandler formSchemaHandler() {
        return new SaveTemplateFormSchemaHandler(templateRepository, formValidation);
    }

    @Test
    void savesTheVettedSchemaInCanonicalForm() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        Map<String, Object> schema = Map.of("type", "object");
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));
        when(formValidation.checkSchema(schema)).thenReturn("{\"type\":\"object\"}");

        formSchemaHandler().handle(new SaveTemplateFormSchemaCommand(tenant, KEY, schema));

        assertThat(template.draftFormSchema()).isEqualTo("{\"type\":\"object\"}");
        verify(templateRepository).saveFormSchemaDraft(template);
    }

    @Test
    void aRefusedSchemaIsAValidationFailureWithTheReasonAndNothingIsStored() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        Map<String, Object> schema = Map.of("type", "object", "pattern", "(a+)+");
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));
        when(formValidation.checkSchema(schema)).thenThrow(new IllegalArgumentException("'pattern' is not allowed"));

        assertThatThrownBy(() -> formSchemaHandler().handle(new SaveTemplateFormSchemaCommand(tenant, KEY, schema)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("'pattern' is not allowed");
        assertThat(template.draftFormSchema()).isNull();
        verify(templateRepository, never()).saveFormSchemaDraft(any());
    }

    @Test
    void aNullSchemaRemovesTheDraftWithoutAskingThePort() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        template.saveFormSchema("{\"type\":\"object\"}");
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));

        formSchemaHandler().handle(new SaveTemplateFormSchemaCommand(tenant, KEY, null));

        assertThat(template.draftFormSchema()).isNull();
        verify(templateRepository).saveFormSchemaDraft(template);
        verifyNoInteractions(formValidation);
    }

    @Test
    void aDisabledTemplateRefusesAFormSchemaChange() {
        ApprovalTemplate template = ApprovalTemplate.start(tenant, KEY);
        template.publish(bpmn);
        template.disable();
        Map<String, Object> schema = Map.of("type", "object");
        when(templateRepository.findByKey(tenant, KEY)).thenReturn(Optional.of(template));
        when(formValidation.checkSchema(schema)).thenReturn("{\"type\":\"object\"}");

        assertThatThrownBy(() -> formSchemaHandler().handle(new SaveTemplateFormSchemaCommand(tenant, KEY, schema)))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.ILLEGAL_STATE));
        verify(templateRepository, never()).saveFormSchemaDraft(any());
    }

    @Test
    void aFormSchemaForAnUnknownTemplateIsNotFound() {
        when(templateRepository.findByKey(tenant, "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> formSchemaHandler().handle(
                new SaveTemplateFormSchemaCommand(tenant, "missing", Map.of("type", "object"))))
                .satisfies(ex -> assertCode(ex, BusinessErrorCode.TEMPLATE_NOT_FOUND));
    }
}
