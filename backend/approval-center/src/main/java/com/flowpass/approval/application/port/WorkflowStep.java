package com.flowpass.approval.application.port;

import java.util.List;

/**
 * What the process engine did in one call: the user tasks it newly opened, and — when the run reached an
 * end node — how it ended.
 *
 * @param newTasks tasks opened by this step (never null)
 * @param outcome  {@code null} while the run is still going; otherwise how it ended
 */
public record WorkflowStep(List<WorkflowTask> newTasks, WorkflowOutcome outcome) {

    public WorkflowStep {
        newTasks = newTasks == null ? List.of() : List.copyOf(newTasks);
    }

    /** True once the run reached an end node. */
    public boolean finished() {
        return outcome != null;
    }
}
