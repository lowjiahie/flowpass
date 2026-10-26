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

    @Test
    void reassignChangesTheAssigneeKeepsTheTaskOpenAndBumpsTheVersion() {
        ApprovalTask t = task();

        t.reassign(new ApproverRef("USER", "u_9"));

        assertThat(t.assignee()).isEqualTo(new ApproverRef("USER", "u_9"));
        assertThat(t.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(t.version()).isEqualTo(1L);
    }

    @Test
    void aClaimedTaskCanBeReassignedToo() {
        ApprovalTask t = task();
        t.claim();

        t.reassign(new ApproverRef("ROLE", "finance"));

        assertThat(t.status()).isEqualTo(TaskStatus.CLAIMED);
        assertThat(t.assignee().type()).isEqualTo("ROLE");
    }

    @Test
    void reassignToTheSameAssigneeOrAFinishedTaskIsRefused() {
        ApprovalTask t = task();
        assertThatThrownBy(() -> t.reassign(new ApproverRef("USER", "u_2"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> t.reassign(null)).isInstanceOf(NullPointerException.class);

        t.approve();
        assertThatThrownBy(() -> t.reassign(new ApproverRef("USER", "u_9"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void returnBackIsTerminalForTheTask() {
        ApprovalTask t = task();

        t.returnBack();

        assertThat(t.status()).isEqualTo(TaskStatus.RETURNED);
        assertThat(t.version()).isEqualTo(1L);
        assertThatThrownBy(t::returnBack).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(t::approve).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> t.reassign(new ApproverRef("USER", "u_9"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anApprovedTaskCannotBeReturned() {
        ApprovalTask t = task();
        t.approve();

        assertThatThrownBy(t::returnBack).isInstanceOf(IllegalStateException.class);
    }

    // ---- deadlines -------------------------------------------------------------------------------------

    private static final java.time.Instant T0 = java.time.Instant.parse("2026-06-01T09:00:00Z");

    @Test
    void aTaskWithoutAnSlaHasNoDeadline() {
        ApprovalTask t = task();

        assertThat(t.dueAt()).isNull();
        assertThat(t.isOverdueAt(T0.plusSeconds(10_000_000))).isFalse();
    }

    @Test
    void aTaskIsOverdueFromItsDeadlineWhileItIsOpen() {
        ApprovalTask t = task();
        t.scheduleDeadlines(T0.plusSeconds(3600), T0.plusSeconds(1800));

        assertThat(t.dueAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(t.remindAt()).isEqualTo(T0.plusSeconds(1800));
        assertThat(t.isOverdueAt(T0.plusSeconds(3599))).isFalse();
        assertThat(t.isOverdueAt(T0.plusSeconds(3600))).isTrue();
        assertThat(t.isOverdueAt(T0.plusSeconds(99999))).isTrue();
    }

    @Test
    void aFinishedTaskIsNeverOverdue() {
        ApprovalTask t = task();
        t.scheduleDeadlines(T0.plusSeconds(3600), null);
        t.approve();

        assertThat(t.isOverdueAt(T0.plusSeconds(99999))).isFalse();
    }
}
