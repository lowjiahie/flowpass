package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Request to withdraw a running approval instance. The acting principal is taken from the authenticated
 * credentials and must be the instance initiator.
 *
 * @param comment optional reason recorded on the audit ledger
 */
public record WithdrawInstanceRequest(@Size(max = 1000) String comment) implements Serializable {

    private static final long serialVersionUID = 1L;
}
