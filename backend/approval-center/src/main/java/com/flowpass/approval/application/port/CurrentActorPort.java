package com.flowpass.approval.application.port;

import com.flowpass.approval.domain.common.ApproverRef;

/**
 * Outbound port exposing the authenticated principal of the current request.
 *
 * <p>Identity is accessed only through ports (charter §6). The actor is derived from the verified
 * credentials — never from a request body — so handlers can trust it for authorization and audit. Like
 * {@link CurrentTenantPort} it is request-scoped; off the request thread the actor must be passed
 * explicitly.</p>
 */
public interface CurrentActorPort {

    /**
     * The authenticated actor of the current request.
     *
     * @throws com.flowpass.approval.application.common.BusinessException with
     *         {@code UNAUTHENTICATED} when there is no authenticated principal
     */
    ApproverRef currentActor();
}
