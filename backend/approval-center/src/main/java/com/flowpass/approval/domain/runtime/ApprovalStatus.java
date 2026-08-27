package com.flowpass.approval.domain.runtime;

/**
 * Business state of an approval instance.
 *
 * <p>These are machine-readable domain states — deliberately distinct from display copy so the
 * contract never leaks UI labels into the domain. {@link com.flowpass.approval.domain.runtime.ApprovalInstance}
 * is the only owner of transitions between them.</p>
 */
public enum ApprovalStatus {
    DRAFT,
    RUNNING,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    TERMINATED
}
