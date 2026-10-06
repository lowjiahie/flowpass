package com.flowpass.approval.domain.definition;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting templates and their published process-definition snapshots.
 *
 * <p>Lives in {@code domain} so the application layer can create, publish and list templates without
 * knowing how they are stored. The adapter in {@code infrastructure} maps to the {@code
 * approval_template} and {@code process_definition} tables.</p>
 */
public interface TemplateRepository {

    /** Resolve a template for a tenant by its unique key, if present. */
    Optional<ApprovalTemplate> findByKey(TenantId tenantId, String templateKey);

    /** List all templates for a tenant, ordered by key. */
    List<ApprovalTemplate> findAll(TenantId tenantId);

    /** Insert a new template (created in {@code DRAFT}). */
    void save(ApprovalTemplate template);

    /** Persist the editable draft definition. */
    void saveDraft(ApprovalTemplate template);

    /** Persist the editable draft form schema (null clears it). */
    void saveFormSchemaDraft(ApprovalTemplate template);

    /**
     * Persist the status and version counter after a publish, guarded at the database level: it only
     * succeeds if no other publish bumped the version in between (otherwise a {@code CONFLICT}).
     */
    void markPublished(ApprovalTemplate template);

    /** Persist a status change (disable / enable). */
    void updateStatus(ApprovalTemplate template);

    /**
     * Persist the immutable process-definition snapshot produced by a publish.
     *
     * @param formSchema  the form schema published with this version, or null when the form is unconstrained;
     *                    stored under the same version number as the definition
     * @param publishedBy who published it (recorded on the version)
     */
    void saveDefinition(String templateId, int version, String bpmnXml, String checksum, String formSchema,
                        ApproverRef publishedBy);
}
