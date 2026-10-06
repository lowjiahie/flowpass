package com.flowpass.approval.domain.decision;

import java.util.Optional;

/**
 * How an approver entry of a decision rule is turned into the people who must act.
 *
 * <ul>
 *   <li>{@code USER} — a fixed user ({@code id} is the user id);</li>
 *   <li>{@code ROLE} — a group: one task for the whole role, any member may act, the first to act wins
 *       ({@code id} is the role name; membership is checked when somebody acts, so staff changes apply);</li>
 *   <li>{@code INITIATOR_MANAGER} — the direct manager of whoever started the request;</li>
 *   <li>{@code DEPARTMENT_HEAD} — the head of the initiator's department;</li>
 *   <li>{@code FORM_FIELD} — the user named in a form variable ({@code id} is the variable name).</li>
 * </ul>
 * The last three are resolved to a concrete {@code USER} when the request starts. For
 * {@code INITIATOR_MANAGER} and {@code DEPARTMENT_HEAD} the {@code id} is not used (by convention
 * {@code initiator}).
 */
public enum ApproverKind {
    USER,
    ROLE,
    INITIATOR_MANAGER,
    DEPARTMENT_HEAD,
    FORM_FIELD;

    public static Optional<ApproverKind> parse(String type) {
        if (type == null) {
            return Optional.empty();
        }
        for (ApproverKind kind : values()) {
            if (kind.name().equals(type)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
