package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * One published, immutable version of a template.
 *
 * @param version         version number (1, 2, ...)
 * @param checksum        SHA-256 of the published definition
 * @param publishedAt     publish time (UTC, ISO-8601)
 * @param publishedByType who published it ({@code USER} / {@code SYSTEM}); null for versions that predate the record
 * @param publishedById   publisher id
 */
public record TemplateVersionDTO(int version, String checksum, String publishedAt, String publishedByType,
                                 String publishedById) implements Serializable {

    private static final long serialVersionUID = 1L;
}
