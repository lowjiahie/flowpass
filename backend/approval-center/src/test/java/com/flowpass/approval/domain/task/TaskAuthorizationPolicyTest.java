package com.flowpass.approval.domain.task;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskAuthorizationPolicyTest {

    private final TenantId tenant = new TenantId("t_1");
    private final ApproverRef assignee = new ApproverRef("USER", "u_2");

    private ApprovalTask task() {
        return ApprovalTask.start(tenant, "appr_1", "Approve", assignee, "approveTask", "wf_task_1");
    }

    @Test
    void assigneeCanAct() {
        assertThat(TaskAuthorizationPolicy.canAct(task(), new ApproverRef("USER", "u_2"))).isTrue();
    }

    @Test
    void anotherUserCannotAct() {
        assertThat(TaskAuthorizationPolicy.canAct(task(), new ApproverRef("USER", "u_9"))).isFalse();
    }

    @Test
    void sameIdWithDifferentTypeCannotAct() {
        assertThat(TaskAuthorizationPolicy.canAct(task(), new ApproverRef("SYSTEM", "u_2"))).isFalse();
    }

    private ApprovalTask roleTask() {
        return ApprovalTask.start(tenant, "appr_1", "Approve", new ApproverRef("ROLE", "finance"), "approveTask", "wf_task_2");
    }

    @Test
    void aMemberOfTheAssignedRoleCanAct() {
        assertThat(TaskAuthorizationPolicy.canAct(roleTask(), new ApproverRef("USER", "u_9"), java.util.Set.of("finance")))
                .isTrue();
    }

    @Test
    void aUserWithoutTheRoleCannotActOnARoleTask() {
        assertThat(TaskAuthorizationPolicy.canAct(roleTask(), new ApproverRef("USER", "u_9"), java.util.Set.of("hr")))
                .isFalse();
        assertThat(TaskAuthorizationPolicy.canAct(roleTask(), new ApproverRef("USER", "u_9"))).isFalse();
    }

    @Test
    void aSystemActorCannotActOnARoleTaskEvenWithTheRoleName() {
        assertThat(TaskAuthorizationPolicy.canAct(roleTask(), new ApproverRef("SYSTEM", "svc"), java.util.Set.of("finance")))
                .isFalse();
    }

    @Test
    void aRoleDoesNotGrantAccessToAUserAssignedTask() {
        assertThat(TaskAuthorizationPolicy.canAct(task(), new ApproverRef("USER", "u_9"), java.util.Set.of("u_2", "finance")))
                .isFalse();
    }

    @Test
    void roleTasksAreRecognised() {
        assertThat(TaskAuthorizationPolicy.isRoleTask(roleTask())).isTrue();
        assertThat(TaskAuthorizationPolicy.isRoleTask(task())).isFalse();
    }

    @Test
    void taskWithoutAssigneeIsNeverActionable() {
        ApprovalTask unassigned = ApprovalTask.hydrate(
                "task_1", tenant, "appr_1", "Approve", null, null, TaskStatus.PENDING, 0L, null, null);

        assertThat(TaskAuthorizationPolicy.canAct(unassigned, assignee)).isFalse();
    }
}
