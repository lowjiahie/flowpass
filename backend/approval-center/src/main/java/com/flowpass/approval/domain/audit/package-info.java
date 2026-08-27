/**
 * Audit bounded context: immutable action records and evidence references.
 *
 * <p>Aggregates: {@code AuditRecord}, {@code EvidenceRef}. Audits are append-only and must not be
 * editable by ordinary administrators. Empty in the M0 skeleton.</p>
 */
package com.flowpass.approval.domain.audit;
