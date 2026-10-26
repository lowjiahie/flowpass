package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Request to hand one of my open tasks to another user. The acting user is taken from the credentials.
 *
 * @param to      the user who takes the task over
 * @param comment optional reason recorded on the audit ledger
 */
public record TransferTaskRequest(@NotBlank @Size(max = 128) String to, @Size(max = 1000) String comment)
        implements Serializable {

    private static final long serialVersionUID = 1L;
}
