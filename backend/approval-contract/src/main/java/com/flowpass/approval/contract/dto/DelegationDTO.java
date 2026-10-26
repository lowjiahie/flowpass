package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * A delegation: while it is active, tasks that open for the delegator go to the delegate.
 *
 * @param startsAt when it starts applying (UTC, ISO-8601)
 * @param endsAt   when it stops applying
 * @param active   true while it applies right now
 */
public record DelegationDTO(String id, String delegatorId, String delegateId, String startsAt, String endsAt,
                            boolean active) implements Serializable {

    private static final long serialVersionUID = 1L;
}
