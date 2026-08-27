/**
 * Task bounded context: assignee resolution, claiming, transfer, completion and countersign stats.
 *
 * <p>Aggregates: {@code ApprovalTask}, {@code Delegation}. Tasks use optimistic versioning to prevent
 * concurrent completion. Empty in the M0 skeleton; filled in as the Phase-1 task capabilities land.</p>
 */
package com.flowpass.approval.domain.task;
