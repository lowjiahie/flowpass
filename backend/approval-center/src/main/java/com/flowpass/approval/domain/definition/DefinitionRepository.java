package com.flowpass.approval.domain.definition;

import com.flowpass.approval.domain.common.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for reading published template definitions.
 *
 * <p>Lives in {@code domain} so the runtime/application layers can pin an instance to a definition
 * version without knowing how definitions are stored. The adapter in {@code infrastructure} resolves
 * the latest published definition for a tenant + template key, and any published version's definition.</p>
 */
public interface DefinitionRepository {

    /** Resolve the latest {@code PUBLISHED} definition for a tenant + template key, if any. */
    Optional<PublishedDefinition> findPublished(TenantId tenantId, String templateKey);

    /** All published versions of a template, newest first (empty when it has none). */
    List<TemplateVersion> findVersions(TenantId tenantId, String templateKey);

    /**
     * The form schema (JSON text) published with one version of a template, if that version has one — an instance
     * pinned to version N is validated against schema N, whatever has been published since.
     */
    Optional<String> findFormSchema(TenantId tenantId, String templateKey, int version);

    /** The process-definition XML of one published version, if that version exists for the tenant. */
    Optional<String> findBpmn(TenantId tenantId, String templateKey, int version);
}
