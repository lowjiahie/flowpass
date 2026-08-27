package com.flowpass.approval.contract.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Public read model of an approval instance.
 *
 * <p>Never exposes domain entities or Flowable internals. This is the single shape returned to
 * external systems and consumed by the BFF.</p>
 *
 * @param id                platform instance id (own id, not a Flowable id)
 * @param businessKey       caller business reference
 * @param templateKey       template this instance runs against
 * @param status            current public status
 * @param initiatorType     {@code USER} or {@code SYSTEM}
 * @param initiatorId       actor id
 * @param amount            optional monetary value (null when the template has no money field)
 * @param currency          ISO-4217 currency code, present only when amount is present
 * @param definitionVersion immutable definition version pinned at start
 * @param createdAt         UTC timestamp (ISO-8601)
 */
public record ApprovalInstanceDTO(
        String id,
        String businessKey,
        String templateKey,
        String status,
        String initiatorType,
        String initiatorId,
        BigDecimal amount,
        String currency,
        Integer definitionVersion,
        String createdAt) implements Serializable {

    private static final long serialVersionUID = 1L;
}
