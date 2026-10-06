package com.flowpass.approval.domain.task;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain tests for the {@link ApprovalTask} aggregate lifecycle. */
class ApprovalTaskTest {

    private final TenantId tenant = new TenantId("t_10086");

    private ApprovalTask task() {
        return ApprovalTask.start(tenant, "appr_biz-1", "Approve purchase",
                new ApproverRef("USER", "u_2"), "approveTask", "wf_task_1");
    }

    @Test
    void startIsPendingAndUnversioned() {
        ApprovalTask t = task();
        assertThat(t.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(t.version()).isZero();
        assertThat(t.assignee().type()).isEqualTo("USER");
        assertThat(t.assignee().id()).isEqualTo("u_2");
        assertThat(t.instanceId()).isEqualTo("appr_biz-1");
    }

    @Test
    void claimMovesPendingToClaimed() {
        ApprovalTask t = task();
        t.claim();
        assertThat(t.status()).isEqualTo(TaskStatus.CLAIMED);
        assertThat(t.version()).isEqualTo(1);
    }

    @Test
    void approveFromPendingAdvancesStatusAndVersion() {
        ApprovalTask t = task();
        t.approve();
        assertThat(t.status()).isEqualTo(TaskStatus.APPROVED);
        assertThat(t.version()).isEqualTo(1);
    }

    @Test
    void approveFromClaimedAdvancesStatusAndVersion() {
        ApprovalTask t = task();
        t.claim();
        t.approve();
        assertThat(t.status()).isEqualTo(TaskStatus.APPROVED);
        assertThat(t.version()).isEqualTo(2);
    }

    @Test
    void cannotApproveATerminalTask() {
        ApprovalTask t = task();
        t.approve();
        assertThatThrownBy(t::approve).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotClaimAClaimedTask() {
        ApprovalTask t = task();
        t.claim();
        assertThatThrownBy(t::claim).isInstanceOf(IllegalStateException.class);
    }
}
