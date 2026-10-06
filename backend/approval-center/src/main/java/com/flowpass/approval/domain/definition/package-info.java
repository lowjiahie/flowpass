/**
 * Definition bounded context: templates, process definitions, form schemas, versioning and publish.
 *
 * <p>Aggregates: {@code ApprovalTemplate} (published definitions are immutable; any change produces a
 * new version). {@code ProcessDefinition} and {@code FormSchema} snapshots are persisted from here as
 * Phase-1 capabilities land.</p>
 */
package com.flowpass.approval.domain.definition;
