package com.flowpass.approval.infrastructure.workflow;

import com.flowpass.approval.infrastructure.persistence.WorkflowRefRow;

import java.util.Optional;

/**
 * Stores the tenant-scoped mapping between a platform approval instance and the engine-local
 * reference. This is an infrastructure concern (charter §4.3): it is only used by the workflow
 * {@code Adapter} and never surfaced through an application {@code Port} to the domain.
 *
 * <p>Deliberately lives outside {@code infrastructure.persistence} so the MyBatis {@code @MapperScan}
 * (which scans that package) never mistakes it for a mapper — only {@code WorkflowRefMapper} is one.</p>
 */
public interface WorkflowRefRepository {

    void save(String tenantId, String instanceId, String definitionKey, Integer definitionVersion,
              String processInstanceId);

    Optional<WorkflowRefRow> findByInstanceId(String tenantId, String instanceId);

    /**
     * Like {@link #findByInstanceId} but locks the row until the surrounding transaction ends. Every step
     * that advances a run takes it first, so two approvers finishing sibling tasks of one run are
     * processed one after the other instead of both counting themselves as the only one done.
     */
    Optional<WorkflowRefRow> findByInstanceIdForUpdate(String tenantId, String instanceId);
}
