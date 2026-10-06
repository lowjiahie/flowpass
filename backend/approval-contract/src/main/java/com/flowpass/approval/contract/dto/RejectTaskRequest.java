package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Request to reject one task. The acting principal is taken from the authenticated credentials, never
 * from this body.
 *
 * @param comment optional reject comment recorded on the audit ledger
 */
public record RejectTaskRequest(@Size(max = 1000) String comment) implements Serializable {

    private static final long serialVersionUID = 1L;
}
