package com.flowpass.approval.infrastructure.persistence;

import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.DefinitionRepository;
import com.flowpass.approval.domain.definition.PublishedDefinition;
import com.flowpass.approval.domain.definition.TemplateVersion;
import org.springframework.stereotype.Repository;

import java.util.List;

import java.util.Optional;

/**
 * Adapter implementing the {@link DefinitionRepository} port on top of MyBatis. Resolves the latest
 * published definition for a tenant + template key and converts the row to the domain value object.
 */
@Repository
public class MybatisDefinitionRepository implements DefinitionRepository {

    private final DefinitionMapper mapper;

    public MybatisDefinitionRepository(DefinitionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PublishedDefinition> findPublished(TenantId tenantId, String templateKey) {
        PublishedDefinitionRow row = mapper.findPublished(tenantId.value(), templateKey);
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public List<TemplateVersion> findVersions(TenantId tenantId, String templateKey) {
        return mapper.findVersions(tenantId.value(), templateKey).stream()
                .map(row -> new TemplateVersion(row.getVersion(), row.getChecksum(), row.getCreatedAt(),
                        row.getPublishedByType(), row.getPublishedById()))
                .toList();
    }

    @Override
    public Optional<String> findFormSchema(TenantId tenantId, String templateKey, int version) {
        return Optional.ofNullable(mapper.findFormSchema(tenantId.value(), templateKey, version));
    }

    @Override
    public Optional<String> findBpmn(TenantId tenantId, String templateKey, int version) {
        return Optional.ofNullable(mapper.findBpmn(tenantId.value(), templateKey, version));
    }

    private PublishedDefinition toDomain(PublishedDefinitionRow row) {
        return new PublishedDefinition(
                row.getTemplateId(),
                row.getTemplateKey(),
                row.getDefinitionVersion(),
                row.getFormVersion(),
                row.getChecksum(),
                row.getStatus());
    }
}
