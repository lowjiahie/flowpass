package com.flowpass.approval.application.query;

import com.flowpass.approval.contract.dto.ApprovalTaskDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.AssigneeFilter;
import com.flowpass.approval.domain.task.TaskRepository;
import com.flowpass.approval.domain.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ListTasksHandler} — paging and DTO mapping. The port is mocked. */
@ExtendWith(MockitoExtension.class)
class ListTasksHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private OrgDirectory orgDirectory;

    private ListTasksHandler handler() {
        return new ListTasksHandler(taskRepository, orgDirectory);
    }

    @Test
    void listsTasksAndMapsToDto() {
        ApprovalTask task = ApprovalTask.start(tenant, "appr_biz-1", "Approve purchase", new ApproverRef("USER", "u_2"), "approveTask", "wf_task_1");
        when(taskRepository.findPage(eq(tenant), eq("appr_biz-1"), eq(TaskStatus.PENDING), isNull(), isNull(), eq(20)))
                .thenReturn(List.of(task));

        List<ApprovalTaskDTO> result = handler().handle(new ListTasksQuery(tenant, "appr_biz-1", "PENDING", null, 20));

        assertThat(result).hasSize(1);
        ApprovalTaskDTO dto = result.get(0);
        assertThat(dto.id()).isEqualTo(task.id());
        assertThat(dto.instanceId()).isEqualTo("appr_biz-1");
        assertThat(dto.name()).isEqualTo("Approve purchase");
        assertThat(dto.assigneeType()).isEqualTo("USER");
        assertThat(dto.assigneeId()).isEqualTo("u_2");
        assertThat(dto.status()).isEqualTo("PENDING");
        assertThat(dto.version()).isEqualTo(0);
    }

    @Test
    void passesNullsWhenFiltersAbsent() {
        when(taskRepository.findPage(eq(tenant), isNull(), isNull(), isNull(), isNull(), eq(20)))
                .thenReturn(List.of());

        List<ApprovalTaskDTO> result = handler().handle(new ListTasksQuery(tenant, null, null, null, 20));

        assertThat(result).isEmpty();
    }

    @Test
    void myTasksIncludeTasksOfTheRolesTheUserHolds() {
        when(orgDirectory.rolesOf(tenant, "u_2")).thenReturn(java.util.Set.of("finance"));
        when(taskRepository.findPage(eq(tenant), isNull(), eq(TaskStatus.PENDING),
                eq(new AssigneeFilter("u_2", java.util.Set.of("finance"))), isNull(), eq(20)))
                .thenReturn(List.of());

        handler().handle(new ListTasksQuery(tenant, null, "PENDING", new ApproverRef("USER", "u_2"), null, 20));

        verify(taskRepository).findPage(eq(tenant), isNull(), eq(TaskStatus.PENDING),
                eq(new AssigneeFilter("u_2", java.util.Set.of("finance"))), isNull(), eq(20));
    }
}
