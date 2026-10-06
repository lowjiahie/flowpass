package com.flowpass.approval.application.port;

/**
 * A resolved approver that should receive a first-round task.
 *
 * @param type approver type ({@code USER} or {@code SYSTEM})
 * @param id   approver id (user id / role / group)
 */
public record CandidateApprover(String type, String id) {
}
