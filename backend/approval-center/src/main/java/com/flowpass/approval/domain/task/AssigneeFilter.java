package com.flowpass.approval.domain.task;

import java.util.Set;

/**
 * "My tasks": tasks assigned to this user, or to any role the user holds.
 *
 * @param userId the user
 * @param roles  roles the user currently holds (may be empty)
 */
public record AssigneeFilter(String userId, Set<String> roles) {

    public AssigneeFilter {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
