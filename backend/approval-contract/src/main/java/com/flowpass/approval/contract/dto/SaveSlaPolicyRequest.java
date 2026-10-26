package com.flowpass.approval.contract.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Request to set the SLA policy of a template. It shapes tasks created after it is saved; open tasks keep the
 * deadlines they were given.
 */
public record SaveSlaPolicyRequest(
        @NotNull @Min(1) @Max(8760) Integer dueAfterHours,
        @Min(1) @Max(8760) Integer remindBeforeHours,
        @NotBlank String onOverdue,
        @Size(max = 128) String escalateToUser) implements Serializable {

    private static final long serialVersionUID = 1L;
}
