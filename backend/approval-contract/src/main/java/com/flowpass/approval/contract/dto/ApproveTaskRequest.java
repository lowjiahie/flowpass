package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Request to approve one task. The acting principal is taken from the authenticated credentials, never
 * from this body.
 *
 * @param comment optional approve comment recorded on the audit ledger
 */
public record ApproveTaskRequest(@Size(max = 1000) String comment) implements Serializable {

    private static final long serialVersionUID = 1L;
}
