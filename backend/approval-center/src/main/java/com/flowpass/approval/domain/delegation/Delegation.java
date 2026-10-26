package com.flowpass.approval.domain.delegation;

import com.flowpass.approval.domain.common.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * "While I am away, my new tasks go to somebody else": a bounded period during which tasks that open for the
 * delegator are assigned to the delegate instead.
 *
 * <p>Invariants: delegator and delegate are different, non-blank users; the period is non-empty, ends in the future
 * and lasts at most {@link #MAX_DAYS} days; a revoked delegation can never be active again. Delegation is one level
 * only — a delegate's own delegation is not followed — so it can never loop.</p>
 */
public final class Delegation {

    public static final int MAX_DAYS = 180;
    private static final int MAX_USER_LENGTH = 128;

    private final String id;
    private final TenantId tenantId;
    private final String delegatorId;
    private final String delegateId;
    private final Instant startsAt;
    private final Instant endsAt;
    private Instant revokedAt;

    private Delegation(String id, TenantId tenantId, String delegatorId, String delegateId, Instant startsAt,
                       Instant endsAt, Instant revokedAt) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.delegatorId = user(delegatorId, "delegator");
        this.delegateId = user(delegateId, "delegate");
        if (this.delegatorId.equals(this.delegateId)) {
            throw new IllegalArgumentException("You cannot delegate to yourself");
        }
        this.startsAt = Objects.requireNonNull(startsAt);
        this.endsAt = Objects.requireNonNull(endsAt);
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("The delegation must end after it starts");
        }
        this.revokedAt = revokedAt;
    }

    /**
     * Create a delegation. A start in the past (or none) means "from now".
     *
     * @throws IllegalArgumentException when the period is empty, already over, or longer than {@link #MAX_DAYS} days
     */
    public static Delegation create(TenantId tenantId, String delegatorId, String delegateId, Instant startsAt,
                                    Instant endsAt, Instant now) {
        Instant start = startsAt == null || startsAt.isBefore(now) ? now : startsAt;
        if (endsAt == null || !endsAt.isAfter(now)) {
            throw new IllegalArgumentException("The delegation must end in the future");
        }
        if (Duration.between(start, endsAt).compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
            throw new IllegalArgumentException("A delegation can last at most " + MAX_DAYS + " days");
        }
        return new Delegation("dlg_" + UUID.randomUUID(), tenantId, delegatorId, delegateId, start, endsAt, null);
    }

    /** Rebuild from a persistence row. */
    public static Delegation hydrate(String id, TenantId tenantId, String delegatorId, String delegateId,
                                     Instant startsAt, Instant endsAt, Instant revokedAt) {
        return new Delegation(id, tenantId, delegatorId, delegateId, startsAt, endsAt, revokedAt);
    }

    private static String user(String value, String what) {
        String user = value == null ? "" : value.trim();
        if (user.isEmpty() || user.length() > MAX_USER_LENGTH) {
            throw new IllegalArgumentException("A valid " + what + " is required");
        }
        return user;
    }

    /** True when the delegation applies at {@code at}: started, not yet ended, not revoked. */
    public boolean isActiveAt(Instant at) {
        return revokedAt == null && !at.isBefore(startsAt) && at.isBefore(endsAt);
    }

    /** True when this (not revoked) delegation overlaps the period {@code [from, to)}. */
    public boolean overlaps(Instant from, Instant to) {
        return revokedAt == null && startsAt.isBefore(to) && endsAt.isAfter(from);
    }

    /** Stop the delegation now. */
    public void revoke(Instant now) {
        if (revokedAt != null) {
            throw new IllegalStateException("The delegation is already revoked");
        }
        this.revokedAt = now;
    }

    public String id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String delegatorId() {
        return delegatorId;
    }

    public String delegateId() {
        return delegateId;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
