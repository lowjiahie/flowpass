package com.flowpass.approval.domain.audit;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, append-only audit entry for an approval action.
 *
 * <p>This is a record of what happened (who, when, what) plus an optional evidence comment. It carries
 * no business behaviour and exposes no mutators: an audit entry is a fact that must never be edited or
 * deleted by ordinary administrators. The aggregate is only created through {@link #of} and persisted
 * via the {@link AuditRecordRepository}. The caller supplies a nullable {@code taskId} for task-scoped
 * actions (approve/reject/withdraw) and {@code null} for instance-scoped ones (start).</p>
 */
public final class AuditRecord {

    private final String id;
    private final TenantId tenantId;
    private final String instanceId;
    private final String taskId;
    private final ApproverRef actor;
    private final AuditAction action;
    private final String comment;
    private final Instant occurredAt;

    private AuditRecord(Builder b) {
        this.id = b.id;
        this.tenantId = b.tenantId;
        this.instanceId = b.instanceId;
        this.taskId = b.taskId;
        this.actor = b.actor;
        this.action = b.action;
        this.comment = b.comment;
        this.occurredAt = b.occurredAt;
    }

    /** Create a new audit entry. {@code taskId} may be null; {@code comment} may be null. */
    public static AuditRecord of(
            TenantId tenantId,
            String instanceId,
            String taskId,
            ApproverRef actor,
            AuditAction action,
            String comment,
            Instant occurredAt) {
        return new Builder()
                .id("aud_" + UUID.randomUUID())
                .tenantId(tenantId)
                .instanceId(instanceId)
                .taskId(taskId)
                .actor(actor)
                .action(action)
                .comment(comment)
                .occurredAt(occurredAt)
                .build();
    }

    /** Re-hydrate a persisted record (loading only, no new id generation). */
    public static AuditRecord hydrate(
            String id,
            TenantId tenantId,
            String instanceId,
            String taskId,
            ApproverRef actor,
            AuditAction action,
            String comment,
            Instant occurredAt) {
        return new Builder()
                .id(id)
                .tenantId(tenantId)
                .instanceId(instanceId)
                .taskId(taskId)
                .actor(actor)
                .action(action)
                .comment(comment)
                .occurredAt(occurredAt)
                .build();
    }

    public String id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String instanceId() {
        return instanceId;
    }

    public String taskId() {
        return taskId;
    }

    public ApproverRef actor() {
        return actor;
    }

    public AuditAction action() {
        return action;
    }

    public String comment() {
        return comment;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    private static final class Builder {
        private String id;
        private TenantId tenantId;
        private String instanceId;
        private String taskId;
        private ApproverRef actor;
        private AuditAction action;
        private String comment;
        private Instant occurredAt;

        Builder id(String id) {
            this.id = id;
            return this;
        }

        Builder tenantId(TenantId tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        Builder actor(ApproverRef actor) {
            this.actor = actor;
            return this;
        }

        Builder action(AuditAction action) {
            this.action = action;
            return this;
        }

        Builder comment(String comment) {
            this.comment = comment;
            return this;
        }

        Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        AuditRecord build() {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(instanceId, "instanceId must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            return new AuditRecord(this);
        }
    }
}
