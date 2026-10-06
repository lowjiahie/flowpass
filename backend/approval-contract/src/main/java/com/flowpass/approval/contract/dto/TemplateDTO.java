package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * Public read model of an approval template (admin view).
 *
 * @param id            platform template id
 * @param templateKey   unique tenant-scoped template key
 * @param status        lifecycle state ({@code DRAFT}, {@code PUBLISHED} or {@code DISABLED})
 * @param latestVersion highest published version, 0 while never published
 */
public record TemplateDTO(String id, String templateKey, String status, int latestVersion) implements Serializable {

    private static final long serialVersionUID = 1L;
}
