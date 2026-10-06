package com.flowpass.approval.application.port;

/**
 * How a workflow run ended — and, as input to {@link WorkflowEnginePort#completeTask}, the decision taken
 * on a task. Machine-readable, never display text.
 */
public enum WorkflowOutcome {
    APPROVED,
    REJECTED
}
