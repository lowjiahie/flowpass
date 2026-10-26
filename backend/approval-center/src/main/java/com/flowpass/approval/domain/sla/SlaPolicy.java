package com.flowpass.approval.domain.sla;

import com.flowpass.approval.domain.common.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How long the tasks of a template may stay open, when to remind the assignee, and what happens when a task is
 * overdue. Time is counted in calendar hours (no working calendar yet) and always in UTC instants.
 *
 * <p>A policy only shapes tasks created after it is saved: each task takes its deadlines from the policy in force
 * when it opens. Invariants: a deadline between 1 hour and one year; a reminder strictly before the deadline;
 * {@code ESCALATE_TO_USER} names the user; {@code NOTIFY_ONLY} names nobody.</p>
 */
public final class SlaPolicy {

    public static final int MAX_HOURS = 8760;

    private final TenantId tenantId;
    private final String templateKey;
    private final int dueAfterHours;
    private final Integer remindBeforeHours;
    private final OverdueAction onOverdue;
    private final String escalateToUser;

    private SlaPolicy(TenantId tenantId, String templateKey, int dueAfterHours, Integer remindBeforeHours,
                      OverdueAction onOverdue, String escalateToUser) {
        this.tenantId = Objects.requireNonNull(tenantId);
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("templateKey must not be blank");
        }
        this.templateKey = templateKey;
        if (dueAfterHours < 1 || dueAfterHours > MAX_HOURS) {
            throw new IllegalArgumentException("dueAfterHours must be between 1 and " + MAX_HOURS);
        }
        this.dueAfterHours = dueAfterHours;
        if (remindBeforeHours != null && (remindBeforeHours < 1 || remindBeforeHours >= dueAfterHours)) {
            throw new IllegalArgumentException("remindBeforeHours must be at least 1 and less than dueAfterHours");
        }
        this.remindBeforeHours = remindBeforeHours;
        this.onOverdue = Objects.requireNonNull(onOverdue, "onOverdue is required");
        String user = escalateToUser == null || escalateToUser.isBlank() ? null : escalateToUser.trim();
        if (user != null && user.length() > 128) {
            throw new IllegalArgumentException("escalateToUser is too long");
        }
        if (onOverdue == OverdueAction.ESCALATE_TO_USER && user == null) {
            throw new IllegalArgumentException("ESCALATE_TO_USER needs escalateToUser");
        }
        if (onOverdue == OverdueAction.NOTIFY_ONLY && user != null) {
            throw new IllegalArgumentException("NOTIFY_ONLY does not take escalateToUser");
        }
        this.escalateToUser = user;
    }

    public static SlaPolicy of(TenantId tenantId, String templateKey, int dueAfterHours, Integer remindBeforeHours,
                               OverdueAction onOverdue, String escalateToUser) {
        return new SlaPolicy(tenantId, templateKey, dueAfterHours, remindBeforeHours, onOverdue, escalateToUser);
    }

    /** When a task that opens at {@code openedAt} is due. */
    public Instant dueAt(Instant openedAt) {
        return openedAt.plus(Duration.ofHours(dueAfterHours));
    }

    /** When to remind the assignee of a task that opens at {@code openedAt}, or null when no reminder is configured. */
    public Instant remindAt(Instant openedAt) {
        return remindBeforeHours == null ? null : dueAt(openedAt).minus(Duration.ofHours(remindBeforeHours));
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String templateKey() {
        return templateKey;
    }

    public int dueAfterHours() {
        return dueAfterHours;
    }

    public Integer remindBeforeHours() {
        return remindBeforeHours;
    }

    public OverdueAction onOverdue() {
        return onOverdue;
    }

    /** The user an overdue task is escalated to (or falls back to), if any. */
    public String escalateToUser() {
        return escalateToUser;
    }
}
