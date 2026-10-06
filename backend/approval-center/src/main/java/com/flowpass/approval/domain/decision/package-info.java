/**
 * Decision bounded context: structured approval-matrix rules and deterministic candidate resolution.
 *
 * <p>Aggregates: {@code DecisionRule} (a structured condition + approvers); the {@code ApprovalMatrix}
 * domain service selects the first matching rule by ascending priority. Rules are deterministic and
 * explicit — no expression engine. Auto-approval and decision-record auditing land later.</p>
 */
package com.flowpass.approval.domain.decision;
