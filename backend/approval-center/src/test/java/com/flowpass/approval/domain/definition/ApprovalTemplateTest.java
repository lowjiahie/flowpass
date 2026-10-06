package com.flowpass.approval.domain.definition;

import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.support.TestBpmn;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain tests for the {@link ApprovalTemplate} aggregate: draft, versioned publishing, disable / enable. */
class ApprovalTemplateTest {

    private static final String KEY = "purchase_request";
    private final TenantId tenant = new TenantId("t_10086");
    private final String bpmn = TestBpmn.purchaseRequest();

    private ApprovalTemplate template() {
        return ApprovalTemplate.start(tenant, KEY);
    }

    @Test
    void startIsDraftWithNoDraftAndNoVersion() {
        ApprovalTemplate t = template();
        assertThat(t.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(t.templateKey()).isEqualTo(KEY);
        assertThat(t.tenantId()).isEqualTo(tenant);
        assertThat(t.id()).startsWith("tpl_");
        assertThat(t.draftBpmn()).isNull();
        assertThat(t.latestVersion()).isZero();
    }

    @Test
    void saveDraftStoresAValidDefinitionWithoutPublishing() {
        ApprovalTemplate t = template();

        t.saveDraft(bpmn);

        assertThat(t.draftBpmn()).isEqualTo(bpmn);
        assertThat(t.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(t.latestVersion()).isZero();
    }

    @Test
    void saveDraftRefusesADefinitionThatBreaksTheFlowContractAndKeepsTheOldDraft() {
        ApprovalTemplate t = template();
        t.saveDraft(bpmn);

        assertThatThrownBy(() -> t.saveDraft(bpmn.replace("${hasApprovers}", "${a.b}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid flow definition");
        assertThat(t.draftBpmn()).isEqualTo(bpmn);
    }

    @Test
    void publishCreatesVersionOneAndMakesTheTemplateRunnable() {
        ApprovalTemplate t = template();

        int version = t.publish(bpmn);

        assertThat(version).isEqualTo(1);
        assertThat(t.latestVersion()).isEqualTo(1);
        assertThat(t.status()).isEqualTo(TemplateStatus.PUBLISHED);
    }

    @Test
    void aTemplateCanBePublishedAgainEachPublishIsTheNextVersion() {
        ApprovalTemplate t = template();
        t.publish(bpmn);

        assertThat(t.publish(bpmn)).isEqualTo(2);
        assertThat(t.publish(bpmn)).isEqualTo(3);
        assertThat(t.latestVersion()).isEqualTo(3);
    }

    @Test
    void anInvalidDefinitionIsNotPublishedAndDoesNotBumpTheVersion() {
        ApprovalTemplate t = template();
        t.publish(bpmn);

        assertThatThrownBy(() -> t.publish("<not-bpmn/>")).isInstanceOf(IllegalArgumentException.class);

        assertThat(t.latestVersion()).isEqualTo(1);
    }

    @Test
    void disableStopsNewInstancesButKeepsTheVersions() {
        ApprovalTemplate t = template();
        t.publish(bpmn);

        t.disable();

        assertThat(t.status()).isEqualTo(TemplateStatus.DISABLED);
        assertThat(t.latestVersion()).isEqualTo(1);
    }

    @Test
    void aDisabledTemplateCanNeitherBeEditedNorPublished() {
        ApprovalTemplate t = template();
        t.publish(bpmn);
        t.disable();

        assertThatThrownBy(() -> t.saveDraft(bpmn)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> t.publish(bpmn)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enableResumesPublishedState() {
        ApprovalTemplate t = template();
        t.publish(bpmn);
        t.disable();

        t.enable();

        assertThat(t.status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(t.publish(bpmn)).isEqualTo(2);
    }

    @Test
    void onlyAPublishedTemplateCanBeDisabledAndOnlyADisabledOneEnabled() {
        ApprovalTemplate draft = template();
        assertThatThrownBy(draft::disable).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(draft::enable).isInstanceOf(IllegalStateException.class);

        ApprovalTemplate published = template();
        published.publish(bpmn);
        assertThatThrownBy(published::enable).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFormSchemaDraftCanBeSavedReplacedAndCleared() {
        ApprovalTemplate t = template();
        assertThat(t.draftFormSchema()).isNull();

        t.saveFormSchema("{\"type\":\"object\"}");
        assertThat(t.draftFormSchema()).isEqualTo("{\"type\":\"object\"}");

        t.saveFormSchema("{\"type\":\"object\",\"required\":[\"a\"]}");
        assertThat(t.draftFormSchema()).contains("required");

        t.saveFormSchema(null);
        assertThat(t.draftFormSchema()).isNull();
    }

    @Test
    void publishingKeepsTheFormSchemaDraftForTheNextVersion() {
        ApprovalTemplate t = template();
        t.saveFormSchema("{\"type\":\"object\"}");

        t.publish(bpmn);

        assertThat(t.draftFormSchema()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void aDisabledTemplateCannotChangeItsFormSchema() {
        ApprovalTemplate t = template();
        t.publish(bpmn);
        t.disable();

        assertThatThrownBy(() -> t.saveFormSchema("{\"type\":\"object\"}")).isInstanceOf(IllegalStateException.class);
    }
}
