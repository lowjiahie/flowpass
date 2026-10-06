package com.flowpass.approval.domain.definition;

/**
 * Lifecycle state of an approval template.
 *
 * <p>{@code DRAFT} has no published version yet and is not runnable; {@code PUBLISHED} has at least one
 * immutable version and is startable (the latest version runs); {@code DISABLED} keeps its versions — running
 * instances are unaffected — but accepts no new instances until it is enabled again.</p>
 */
public enum TemplateStatus {
    DRAFT,
    PUBLISHED,
    DISABLED
}
