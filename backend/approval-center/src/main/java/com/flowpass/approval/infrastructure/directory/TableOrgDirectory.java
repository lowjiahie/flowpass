package com.flowpass.approval.infrastructure.directory;

import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;
import com.flowpass.approval.infrastructure.persistence.DirectoryMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

/**
 * Temporary {@link OrgDirectory} backed by the {@code directory_*} tables.
 *
 * <p>TODO(keycloak): replace with an adapter over the identity platform's organisation / groups; the domain
 * only depends on the port.</p>
 */
@Repository
public class TableOrgDirectory implements OrgDirectory {

    private final DirectoryMapper mapper;

    public TableOrgDirectory(DirectoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<String> managerOf(TenantId tenantId, String userId) {
        return nonBlank(mapper.managerOf(tenantId.value(), userId));
    }

    @Override
    public Optional<String> departmentHeadOf(TenantId tenantId, String userId) {
        return nonBlank(mapper.departmentHeadOf(tenantId.value(), userId));
    }

    @Override
    public Set<String> rolesOf(TenantId tenantId, String userId) {
        return Set.copyOf(mapper.rolesOf(tenantId.value(), userId));
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
