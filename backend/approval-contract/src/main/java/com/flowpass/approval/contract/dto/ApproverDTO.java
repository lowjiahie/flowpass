package com.flowpass.approval.contract.dto;

import java.io.Serializable;

/**
 * An approver reference inside a decision rule: {@code USER} or {@code SYSTEM}.
 *
 * @param type approver type ({@code USER} or {@code SYSTEM})
 * @param id   approver id (user id / role / group)
 */
public record ApproverDTO(String type, String id) implements Serializable {

    private static final long serialVersionUID = 1L;
}
