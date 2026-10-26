package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * The SLA policy of a template: how long its tasks may stay open and what happens when they are overdue.
 *
 * @param dueAfterHours       hours after a task opens until it is due
 * @param remindBeforeHours   hours before the deadline to remind the assignee, or null for no reminder
 * @param onOverdue           {@code NOTIFY_ONLY}, {@code ESCALATE_TO_MANAGER} or {@code ESCALATE_TO_USER}
 * @param escalateToUser      the user an overdue task is escalated to (the fallback for {@code ESCALATE_TO_MANAGER})
 */
public record SlaPolicyDTO(String templateKey, int dueAfterHours, Integer remindBeforeHours, String onOverdue,
                           String escalateToUser) implements Serializable {

    private static final long serialVersionUID = 1L;
}
