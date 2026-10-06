package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;
import com.flowpass.approval.domain.runtime.ApprovalId;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalStatus;
import com.flowpass.approval.domain.task.ApprovalTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link TaskActionAuthorizer}: direct assignment, role membership, and no self-approval via a role. */
@ExtendWith(MockitoExtension.class)
class TaskActionAuthorizerTest {

    private final TenantId tenant = new TenantId("t_1");
    private final ApproverRef initiator = new ApproverRef("USER", "u_1");

    @Mock
    private OrgDirectory orgDirectory;

    private ApprovalInstance instance() {
        return ApprovalInstance.hydrate(new ApprovalId("appr_1"), tenant, "app", "biz-1", "purchase_request",
                initiator, null, 1, Map.of(), ApprovalStatus.RUNNING, 0L);
    }

    private ApprovalTask task(ApproverRef assignee) {
        return ApprovalTask.start(tenant, "appr_1", "Approve", assignee, "approveTask", "wf_1");
    }

    private static void assertForbidden(Throwable ex) {
        assertThat(ex).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(BusinessErrorCode.FORBIDDEN));
    }

    @Test
    void theAssigneeOfAUserTaskMayActWithoutAnyDirectoryLookup() {
        TaskActionAuthorizer authorizer = new TaskActionAuthorizer(orgDirectory);

        assertThatCode(() -> authorizer.requireCanAct(
                task(new ApproverRef("USER", "u_2")), instance(), new ApproverRef("USER", "u_2"), "approve"))
                .doesNotThrowAnyException();
        verifyNoInteractions(orgDirectory);
    }

    @Test
    void anotherUserMayNotActOnAUserTask() {
        TaskActionAuthorizer authorizer = new TaskActionAuthorizer(orgDirectory);

        assertThatThrownBy(() -> authorizer.requireCanAct(
                task(new ApproverRef("USER", "u_2")), instance(), new ApproverRef("USER", "u_9"), "approve"))
                .satisfies(TaskActionAuthorizerTest::assertForbidden);
    }

    @Test
    void aMemberOfTheRoleMayActOnARoleTask() {
        when(orgDirectory.rolesOf(tenant, "u_9")).thenReturn(Set.of("finance"));

        assertThatCode(() -> new TaskActionAuthorizer(orgDirectory).requireCanAct(
                task(new ApproverRef("ROLE", "finance")), instance(), new ApproverRef("USER", "u_9"), "approve"))
                .doesNotThrowAnyException();
    }

    @Test
    void aNonMemberMayNotActOnARoleTask() {
        when(orgDirectory.rolesOf(tenant, "u_9")).thenReturn(Set.of("hr"));

        assertThatThrownBy(() -> new TaskActionAuthorizer(orgDirectory).requireCanAct(
                task(new ApproverRef("ROLE", "finance")), instance(), new ApproverRef("USER", "u_9"), "reject"))
                .satisfies(TaskActionAuthorizerTest::assertForbidden);
    }

    @Test
    void theInitiatorMayNotApproveTheirOwnRequestThroughARoleTheyHold() {
        when(orgDirectory.rolesOf(tenant, "u_1")).thenReturn(Set.of("finance"));

        assertThatThrownBy(() -> new TaskActionAuthorizer(orgDirectory).requireCanAct(
                task(new ApproverRef("ROLE", "finance")), instance(), initiator, "approve"))
                .satisfies(TaskActionAuthorizerTest::assertForbidden)
                .hasMessageContaining("own request");
    }

    @Test
    void aSystemActorGetsNoRoleLookupAndNoAccess() {
        assertThatThrownBy(() -> new TaskActionAuthorizer(orgDirectory).requireCanAct(
                task(new ApproverRef("ROLE", "finance")), instance(), new ApproverRef("SYSTEM", "svc"), "approve"))
                .satisfies(TaskActionAuthorizerTest::assertForbidden);
        verifyNoInteractions(orgDirectory);
    }
}
