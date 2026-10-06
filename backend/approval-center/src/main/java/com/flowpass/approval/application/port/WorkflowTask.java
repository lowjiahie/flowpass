package com.flowpass.approval.application.port;

/**
 * A user task opened by the process engine, as seen by the application layer.
 *
 * @param ref          opaque engine reference — only meaningful to {@link WorkflowEnginePort}; never exposed
 *                     through the public API
 * @param nodeKey      process node (BPMN element id) the task belongs to
 * @param name         task display name from the definition
 * @param assigneeType assignee type ({@code USER}, ...) or null when the engine task is unassigned
 * @param assigneeId   assignee id, or null when unassigned
 */
public record WorkflowTask(String ref, String nodeKey, String name, String assigneeType, String assigneeId) {

    /** True when the engine assigned this task to somebody. */
    public boolean assigned() {
        return assigneeType != null && assigneeId != null;
    }
}
