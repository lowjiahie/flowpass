package com.flowpass.approval.contract.enums;

/**
 * Public approval instance status exposed on the API contract.
 *
 * <p>Kept separate from the internal domain state machine on purpose: the contract is a stable,
 * publicly consumable vocabulary that must not shift when the internal domain evolves. Do not add
 * display copy here; these are machine-readable business states.</p>
 */
public enum ApprovalStatus {
    DRAFT,
    RUNNING,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    TERMINATED
}
