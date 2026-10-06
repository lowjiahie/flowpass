package com.flowpass.approval.domain.definition;

import java.time.Instant;

/**
 * One immutable published version of a template: what was published, when and by whom. The definition
 * itself is read separately (it can be large).
 */
public record TemplateVersion(
        int version,
        String checksum,
        Instant publishedAt,
        String publishedByType,
        String publishedById) {
}
