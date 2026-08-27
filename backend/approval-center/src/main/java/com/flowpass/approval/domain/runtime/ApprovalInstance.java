package com.flowpass.approval.domain.runtime;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.Money;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.event.ApprovalInstanceApproved;
import com.flowpass.approval.domain.event.ApprovalInstanceCreated;
import com.flowpass.approval.domain.event.ApprovalInstanceRejected;
import com.flowpass.approval.domain.event.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregate root for an approval run — the single owner of its state changes.
 *
 * <p>Invariants enforced here: a running instance may be approved or rejected exactly once; a
 * terminal instance cannot be mutated; every transition bumps {@code version} for optimistic locking
 * and registers a past-tense domain event. State must never be mutated outside this aggregate.</p>
 */
public class ApprovalInstance {

    private final ApprovalId id;
    private final TenantId tenantId;
    private final String applicationId;
    private final String businessKey;
    private final String templateKey;
    private final ApproverRef initiator;
    private final Money money;
    private final Integer definitionVersion;

    private ApprovalStatus status;
    private long version;
    private final List<DomainEvent> events;

    private ApprovalInstance(Builder b) {
        this.id = b.id;
        this.tenantId = b.tenantId;
        this.applicationId = b.applicationId;
        this.businessKey = b.businessKey;
        this.templateKey = b.templateKey;
        this.initiator = b.initiator;
        this.money = b.money;
        this.definitionVersion = b.definitionVersion;
        this.status = b.status;
        this.version = b.version;
        this.events = new ArrayList<>();
    }

    /** Start a new instance. It begins in {@code RUNNING} and emits {@link ApprovalInstanceCreated}. */
    public static ApprovalInstance start(
            TenantId tenantId,
            String applicationId,
            String businessKey,
            String templateKey,
            ApproverRef initiator,
            Money money,
            Integer definitionVersion) {
        ApprovalInstance instance = new Builder()
                .id(new ApprovalId("appr_" + businessKey))
                .tenantId(tenantId)
                .applicationId(applicationId)
                .businessKey(businessKey)
                .templateKey(templateKey)
                .initiator(initiator)
                .money(money)
                .definitionVersion(definitionVersion)
                .status(ApprovalStatus.RUNNING)
                .version(0L)
                .build();
        instance.register(new ApprovalInstanceCreated(instance.id.value(), tenantId.value(), businessKey, Instant.now()));
        return instance;
    }

    /** Re-hydrate an existing aggregate from persistence (loading only, no event side effects). */
    public static ApprovalInstance hydrate(
            ApprovalId id,
            TenantId tenantId,
            String applicationId,
            String businessKey,
            String templateKey,
            ApproverRef initiator,
            Money money,
            Integer definitionVersion,
            ApprovalStatus status,
            long version) {
        return new Builder()
                .id(id)
                .tenantId(tenantId)
                .applicationId(applicationId)
                .businessKey(businessKey)
                .templateKey(templateKey)
                .initiator(initiator)
                .money(money)
                .definitionVersion(definitionVersion)
                .status(status)
                .version(version)
                .build();
    }

    public void approve(ApproverRef actor) {
        transitionTo(ApprovalStatus.APPROVED, new ApprovalInstanceApproved(id.value(), tenantId.value(), actor.id(), Instant.now()));
    }

    public void reject(ApproverRef actor) {
        transitionTo(ApprovalStatus.REJECTED, new ApprovalInstanceRejected(id.value(), tenantId.value(), actor.id(), Instant.now()));
    }

    private void transitionTo(ApprovalStatus next, DomainEvent event) {
        if (status != ApprovalStatus.RUNNING) {
            throw new IllegalStateException("Cannot transition from " + status + " to " + next);
        }
        this.status = next;
        this.version++;
        register(event);
    }

    private void register(DomainEvent event) {
        this.events.add(event);
    }

    /** Return and clear the pending domain events (called by the app layer before save / outbox). */
    public List<DomainEvent> drainEvents() {
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    public ApprovalId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String applicationId() {
        return applicationId;
    }

    public String businessKey() {
        return businessKey;
    }

    public String templateKey() {
        return templateKey;
    }

    public ApproverRef initiator() {
        return initiator;
    }

    public Money money() {
        return money;
    }

    public Integer definitionVersion() {
        return definitionVersion;
    }

    public ApprovalStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    private static final class Builder {
        private ApprovalId id;
        private TenantId tenantId;
        private String applicationId;
        private String businessKey;
        private String templateKey;
        private ApproverRef initiator;
        private Money money;
        private Integer definitionVersion;
        private ApprovalStatus status;
        private long version;

        Builder id(ApprovalId id) { this.id = id; return this; }
        Builder tenantId(TenantId tenantId) { this.tenantId = tenantId; return this; }
        Builder applicationId(String applicationId) { this.applicationId = applicationId; return this; }
        Builder businessKey(String businessKey) { this.businessKey = businessKey; return this; }
        Builder templateKey(String templateKey) { this.templateKey = templateKey; return this; }
        Builder initiator(ApproverRef initiator) { this.initiator = initiator; return this; }
        Builder money(Money money) { this.money = money; return this; }
        Builder definitionVersion(Integer definitionVersion) { this.definitionVersion = definitionVersion; return this; }
        Builder status(ApprovalStatus status) { this.status = status; return this; }
        Builder version(long version) { this.version = version; return this; }

        ApprovalInstance build() {
            if (id == null || tenantId == null || status == null) {
                throw new IllegalArgumentException("id, tenantId and status are required");
            }
            return new ApprovalInstance(this);
        }
    }
}
