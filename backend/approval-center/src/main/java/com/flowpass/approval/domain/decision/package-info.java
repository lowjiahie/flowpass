/**
 * Decision bounded context: CEL conditions, approval matrices and deterministic auto-approval.
 *
 * <p>Aggregates: {@code RuleSet}, {@code DecisionTable}, {@code DecisionResult}. Auto-approval is
 * deterministic and audit-logged; rule errors fall back to manual review. Empty in the M0 skeleton.</p>
 */
package com.flowpass.approval.domain.decision;
