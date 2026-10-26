package com.flowpass.approval.contract.dto;

import java.io.Serializable;
import java.util.List;

/**
 * Dry-run result of approver resolution.
 *
 * @param approvers the principals a real start would assign ({@code USER} or {@code ROLE}) across all rounds; empty when none
 * @param rounds    the same principals grouped by approval round, in the order the rounds open
 * @param problems  why resolution failed — in that case a real start would route to manual review
 */
public record ApproverPreviewDTO(List<ApproverDTO> approvers, List<List<ApproverDTO>> rounds, List<String> problems)
        implements Serializable {

    /** A preview without a round breakdown. */
    public ApproverPreviewDTO(List<ApproverDTO> approvers, List<String> problems) {
        this(approvers, approvers.isEmpty() ? List.of() : List.of(approvers), problems);
    }

    private static final long serialVersionUID = 1L;
}
