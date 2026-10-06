package com.flowpass.approval.support;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.RuntimeService;

/** Test housekeeping for the process engine. */
public final class EngineTestSupport {

    private EngineTestSupport() {
    }

    /**
     * Remove leftover runs between tests. Flowable cannot delete a parallel step that is partly completed
     * (the adapter withdraws through the model instead), so such a run is left behind: tests use unique
     * business keys and never depend on the engine being empty.
     */
    public static void clearRuns(RuntimeService runtimeService) {
        runtimeService.createProcessInstanceQuery().list().forEach(pi -> {
            try {
                runtimeService.deleteProcessInstance(pi.getId(), "test cleanup");
            } catch (FlowableOptimisticLockingException ignored) {
                // partly completed parallel step — see above
            }
        });
    }
}
