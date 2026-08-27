package com.flowpass.approval.domain.common;

import java.util.Objects;

/**
 * Reference to a human or system principal in the approval domain.
 *
 * <p>Deliberately free of any identity-provider specifics (Keycloak, SSO). {@code type} is a
 * domain-wide discriminator such as {@code USER} or {@code SYSTEM}; the underlying identity is
 * resolved through an identity adapter behind a port, never inside the domain.</p>
 */
public record ApproverRef(String type, String id) {

    public ApproverRef {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
        if (type.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("type and id must not be blank");
        }
    }
}
