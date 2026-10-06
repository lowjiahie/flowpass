package com.flowpass.approval.contract.dto;

import java.io.Serializable;
import java.time.Instant;

/**
 * Read model for an immutable audit entry (approval action timeline).
 *
 * <p>Exposes the who/when/what plus the optional evidence comment. Never surfaces an aggregate or an
 * internal row; this is the contract the admin/ops UI consumes.</p>
 */
public record AuditRecordDTO(
        String id,
        String tenantId,
        String instanceId,
        String taskId,
        String actorType,
        String actorId,
        String action,
        String comment,
        Instant occurredAt) implements Serializable {

    private static final long serialVersionUID = 1L;
}
