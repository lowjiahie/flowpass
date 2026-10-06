package com.flowpass.approval.domain.directory;

import com.flowpass.approval.domain.common.TenantId;

import java.util.Optional;
import java.util.Set;

/**
 * Outbound port: who reports to whom, who heads a department, who holds a role.
 *
 * <p>Organisation data belongs to the identity platform, not to the approval service; the domain only asks
 * these questions. Everything is tenant-scoped. TODO(keycloak): until the identity service provides the
 * organisation, a temporary table-backed adapter answers them.</p>
 */
public interface OrgDirectory {

    /** The direct manager of a user, if one is recorded. */
    Optional<String> managerOf(TenantId tenantId, String userId);

    /** The head of the user's department, if the user has a department with a head. */
    Optional<String> departmentHeadOf(TenantId tenantId, String userId);

    /** The roles a user currently holds (empty when none or unknown). */
    Set<String> rolesOf(TenantId tenantId, String userId);
}
