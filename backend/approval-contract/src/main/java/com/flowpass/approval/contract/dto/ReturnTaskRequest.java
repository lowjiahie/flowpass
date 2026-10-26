package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Request to send the request back to the previous approval round. The acting user is taken from the credentials.
 *
 * @param comment optional reason recorded on the audit ledger
 */
public record ReturnTaskRequest(@Size(max = 1000) String comment) implements Serializable {

    private static final long serialVersionUID = 1L;
}
