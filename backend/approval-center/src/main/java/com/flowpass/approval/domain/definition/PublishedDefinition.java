package com.flowpass.approval.domain.definition;

/**
 * Immutable snapshot of a published template definition that an instance is started against.
 *
 * <p>An instance pins this at start time so later edits or new versions of the template never affect a
 * running instance. {@code formVersion} may be null when the template has no published form schema;
 * {@code definitionVersion} is the pinned {@code process_definition.version}.</p>
 */
public record PublishedDefinition(
        String templateId,
        String templateKey,
        Integer definitionVersion,
        Integer formVersion,
        String checksum,
        String status) {
}
