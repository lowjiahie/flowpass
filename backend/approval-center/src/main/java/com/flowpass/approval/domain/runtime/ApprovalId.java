package com.flowpass.approval.domain.runtime;

import java.util.Objects;

/** Identifier of an approval instance. Own platform id; never a Flowable id. */
public record ApprovalId(String value) {

    public ApprovalId {
        Objects.requireNonNull(value, "approvalId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
    }
}
