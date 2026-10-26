package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * An approver reference inside a decision rule: {@code USER} or {@code SYSTEM}.
 *
 * @param type approver type ({@code USER} or {@code SYSTEM})
 * @param id   approver id (user id / role / group)
 * @param round approval round (1 to 5) this approver belongs to; omitted / null means round 1
 */
public record ApproverDTO(String type, String id, Integer round) implements Serializable {

    /** An approver of the first (or only) round. */
    public ApproverDTO(String type, String id) {
        this(type, id, null);
    }

    private static final long serialVersionUID = 1L;
}
