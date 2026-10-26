package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.time.Instant;

/**
 * Request to delegate my new tasks to somebody else for a while. The delegator is the caller.
 *
 * @param delegateId who receives my tasks
 * @param startsAt   when it starts (optional; a start in the past or none means now)
 * @param endsAt     when it ends — in the future, and at most 180 days after the start
 */
public record CreateDelegationRequest(@NotBlank @Size(max = 128) String delegateId, Instant startsAt,
                                      @NotNull Instant endsAt) implements Serializable {

    private static final long serialVersionUID = 1L;
}
