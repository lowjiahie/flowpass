package com.flowpass.approval.domain.definition;

import com.flowpass.approval.domain.common.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root in the {@code definition} bounded context.
 *
 * <p>A reusable approval template, uniquely keyed per tenant. It owns an editable <em>draft</em> of its
 * process definition and a counter of immutable published versions:</p>
 * <ul>
 *   <li>{@link #saveDraft} stores a new draft (validated against the platform's flow contract);</li>
 *   <li>{@link #publish} turns a definition into the next immutable version — a template can be published
 *       any number of times, each publish bumping {@code latestVersion}; running instances keep the version
 *       they started on;</li>
 *   <li>{@link #disable} / {@link #enable} stop / resume new instances without touching any version.</li>
 * </ul>
 */
public final class ApprovalTemplate {

    private final String id;
    private final TenantId tenantId;
    private final String templateKey;
    private TemplateStatus status;
    private String draftBpmn;
    private String draftFormSchema;
    private int latestVersion;

    private ApprovalTemplate(String id, TenantId tenantId, String templateKey, TemplateStatus status,
                             String draftBpmn, String draftFormSchema, int latestVersion) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.templateKey = Objects.requireNonNull(templateKey);
        this.status = Objects.requireNonNull(status);
        this.draftBpmn = draftBpmn;
        this.draftFormSchema = draftFormSchema;
        this.latestVersion = latestVersion;
    }

    /** Create a new template in {@code DRAFT} state with no draft and no version. */
    public static ApprovalTemplate start(TenantId tenantId, String templateKey) {
        return new ApprovalTemplate("tpl_" + UUID.randomUUID(), tenantId, templateKey, TemplateStatus.DRAFT, null, null, 0);
    }

    /** Rebuild an aggregate from a persistence row. */
    public static ApprovalTemplate hydrate(String id, TenantId tenantId, String templateKey, TemplateStatus status,
                                           String draftBpmn, String draftFormSchema, int latestVersion) {
        return new ApprovalTemplate(id, tenantId, templateKey, status, draftBpmn, draftFormSchema, latestVersion);
    }

    /**
     * Replace the editable draft. The definition must satisfy {@link FlowDefinitionValidator}; a disabled
     * template cannot be edited.
     *
     * @throws IllegalArgumentException when the definition breaks the flow contract
     * @throws IllegalStateException    when the template is disabled
     */
    public void saveDraft(String bpmnXml) {
        requireNotDisabled("edit");
        FlowDefinitionValidator.validate(templateKey, bpmnXml);
        this.draftBpmn = bpmnXml;
    }

    /**
     * Replace (or, with {@code null}, remove) the draft of the application form's JSON Schema. The text must
     * already have been vetted (valid, supported, safe) by the form-validation port; the aggregate only owns the
     * lifecycle rule that a disabled template cannot be edited.
     *
     * @throws IllegalStateException when the template is disabled
     */
    public void saveFormSchema(String schemaJson) {
        requireNotDisabled("edit");
        this.draftFormSchema = schemaJson;
    }

    /**
     * Publish {@code bpmnXml} as the next immutable version and make the template runnable.
     *
     * @return the new version number
     * @throws IllegalArgumentException when the definition breaks the flow contract
     * @throws IllegalStateException    when the template is disabled
     */
    public int publish(String bpmnXml) {
        requireNotDisabled("publish");
        FlowDefinitionValidator.validate(templateKey, bpmnXml);
        this.latestVersion++;
        this.status = TemplateStatus.PUBLISHED;
        return latestVersion;
    }

    /** Stop accepting new instances ({@code PUBLISHED → DISABLED}); versions and running instances are untouched. */
    public void disable() {
        if (status != TemplateStatus.PUBLISHED) {
            throw new IllegalStateException("Cannot disable a template in " + status);
        }
        this.status = TemplateStatus.DISABLED;
    }

    /** Resume accepting new instances ({@code DISABLED → PUBLISHED}). */
    public void enable() {
        if (status != TemplateStatus.DISABLED) {
            throw new IllegalStateException("Cannot enable a template in " + status);
        }
        this.status = TemplateStatus.PUBLISHED;
    }

    private void requireNotDisabled(String action) {
        if (status == TemplateStatus.DISABLED) {
            throw new IllegalStateException("Cannot " + action + " a disabled template");
        }
    }

    public String id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String templateKey() {
        return templateKey;
    }

    public TemplateStatus status() {
        return status;
    }

    /** The editable draft, or null when none was saved (publish then falls back to the built-in definition). */
    public String draftBpmn() {
        return draftBpmn;
    }

    /**
     * The editable draft of the application form's JSON Schema, or null when the form is unconstrained. Whatever
     * it is when a version is published becomes that version's immutable form schema.
     */
    public String draftFormSchema() {
        return draftFormSchema;
    }

    /** Highest published version, 0 while the template has never been published. */
    public int latestVersion() {
        return latestVersion;
    }
}
