package com.flowpass.approval.infrastructure.workflow;

import com.flowpass.approval.application.port.WorkflowOutcome;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.definition.DefinitionRepository;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the guard rails of the Flowable adapter: every step on a run first takes the run's row
 * lock, and an unknown run is refused before the engine is touched. The engine behaviour itself
 * (tasks, outcomes, tenant scoping, concurrency) is covered against a real engine in
 * {@code WorkflowEngineIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class FlowableWorkflowEngineAdapterTest {

    private static final TenantId TENANT = new TenantId("t_1");
    private static final String INSTANCE_ID = "appr_biz-1";

    @Mock
    private RepositoryService repositoryService;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private TaskService taskService;
    @Mock
    private HistoryService historyService;
    @Mock
    private DefinitionRepository definitionRepository;
    @Mock
    private WorkflowRefRepository workflowRefRepository;

    private FlowableWorkflowEngineAdapter adapter() {
        return new FlowableWorkflowEngineAdapter(
                repositoryService, runtimeService, taskService, historyService, definitionRepository, workflowRefRepository);
    }

    @Test
    void terminateThrowsWhenNoWorkflowRef() {
        when(workflowRefRepository.findByInstanceIdForUpdate(TENANT.value(), INSTANCE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter().terminate(TENANT, INSTANCE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No workflow ref for instance " + INSTANCE_ID);
        verifyNoInteractions(runtimeService);
    }

    @Test
    void completeTaskThrowsWhenNoWorkflowRef() {
        when(workflowRefRepository.findByInstanceIdForUpdate(TENANT.value(), INSTANCE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter().completeTask(TENANT, INSTANCE_ID, "wf_1", WorkflowOutcome.APPROVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No workflow ref for instance " + INSTANCE_ID);
        verifyNoInteractions(taskService, runtimeService);
    }
}
