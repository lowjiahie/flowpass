package com.flowpass.approval.application.query;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.contract.dto.ApprovalTaskDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Unit tests for {@link GetTaskHandler} — single-task fetch and the not-found path. The port is mocked. */
@ExtendWith(MockitoExtension.class)
class GetTaskHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");

    @Mock
    private TaskRepository taskRepository;

    private GetTaskHandler handler() {
        return new GetTaskHandler(taskRepository);
    }

    @Test
    void returnsTask() {
        ApprovalTask task = ApprovalTask.start(tenant, "appr_biz-1", "Approve purchase", new ApproverRef("USER", "u_2"), "approveTask", "wf_task_1");
        when(taskRepository.findById(eq(tenant), eq(task.id()))).thenReturn(Optional.of(task));

        ApprovalTaskDTO dto = handler().handle(new GetTaskQuery(tenant, task.id()));

        assertThat(dto.id()).isEqualTo(task.id());
        assertThat(dto.instanceId()).isEqualTo("appr_biz-1");
        assertThat(dto.name()).isEqualTo("Approve purchase");
        assertThat(dto.assigneeType()).isEqualTo("USER");
        assertThat(dto.assigneeId()).isEqualTo("u_2");
        assertThat(dto.status()).isEqualTo("PENDING");
    }

    @Test
    void throwsWhenTaskNotFound() {
        when(taskRepository.findById(eq(tenant), eq("task_missing"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler().handle(new GetTaskQuery(tenant, "task_missing")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(BusinessErrorCode.TASK_NOT_FOUND));
    }
}
