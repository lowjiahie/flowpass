package com.flowpass.approval.contract.dto;

import java.io.Serializable;
import java.util.List;

/**
 * Dry-run result of approver resolution.
 *
 * @param approvers the principals a real start would assign ({@code USER} or {@code ROLE}); empty when none
 * @param problems  why resolution failed — in that case a real start would route to manual review
 */
public record ApproverPreviewDTO(List<ApproverDTO> approvers, List<String> problems) implements Serializable {

    private static final long serialVersionUID = 1L;
}
