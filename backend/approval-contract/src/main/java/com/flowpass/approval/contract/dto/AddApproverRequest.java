package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Request to ask another user to approve as well (parallel add-sign): the approval round the task belongs to now
 * also needs the new approver's approval. The acting user is taken from the credentials.
 *
 * @param userId  the user who is added to the round
 * @param comment optional reason recorded on the audit ledger
 */
public record AddApproverRequest(@NotBlank @Size(max = 128) String userId, @Size(max = 1000) String comment)
        implements Serializable {

    private static final long serialVersionUID = 1L;
}
