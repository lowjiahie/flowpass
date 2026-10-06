package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.ApprovalTemplate;
import com.flowpass.approval.domain.definition.TemplateRepository;
import com.flowpass.approval.domain.definition.TemplateStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Use-case tests for {@link CreateTemplateHandler} — happy path plus duplicate-key rejection. */
@ExtendWith(MockitoExtension.class)
class CreateTemplateHandlerTest {

    @Mock
    private TemplateRepository templateRepository;

    private final TenantId tenant = new TenantId("t_10086");

    private CreateTemplateHandler handler() {
        return new CreateTemplateHandler(templateRepository);
    }

    @Test
    void createsDraftTemplate() {
        when(templateRepository.findByKey(tenant, "purchase_request")).thenReturn(Optional.empty());

        TemplateDTO dto = handler().handle(new CreateTemplateCommand(tenant, "purchase_request"));

        assertThat(dto.status()).isEqualTo("DRAFT");
        assertThat(dto.templateKey()).isEqualTo("purchase_request");

        ArgumentCaptor<ApprovalTemplate> captor = ArgumentCaptor.forClass(ApprovalTemplate.class);
        verify(templateRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(captor.getValue().tenantId()).isEqualTo(tenant);
    }

    @Test
    void rejectsDuplicateKey() {
        when(templateRepository.findByKey(tenant, "purchase_request"))
                .thenReturn(Optional.of(ApprovalTemplate.start(tenant, "purchase_request")));

        assertThatThrownBy(() -> handler().handle(new CreateTemplateCommand(tenant, "purchase_request")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }
}
