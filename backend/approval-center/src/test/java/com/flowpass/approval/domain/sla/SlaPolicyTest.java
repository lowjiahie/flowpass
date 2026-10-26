package com.flowpass.approval.domain.sla;

import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlaPolicyTest {

    private final TenantId tenant = new TenantId("t_1");
    private final Instant opened = Instant.parse("2026-06-01T09:00:00Z");

    private SlaPolicy policy(int due, Integer remind, OverdueAction action, String user) {
        return SlaPolicy.of(tenant, "purchase_request", due, remind, action, user);
    }

    @Test
    void deadlinesAreCountedFromWhenTheTaskOpens() {
        SlaPolicy p = policy(48, 6, OverdueAction.NOTIFY_ONLY, null);

        assertThat(p.dueAt(opened)).isEqualTo(Instant.parse("2026-06-03T09:00:00Z"));
        assertThat(p.remindAt(opened)).isEqualTo(Instant.parse("2026-06-03T03:00:00Z"));
    }

    @Test
    void noReminderMeansNoReminderTime() {
        assertThat(policy(24, null, OverdueAction.NOTIFY_ONLY, null).remindAt(opened)).isNull();
    }

    @Test
    void theDeadlineMustBeBetweenOneHourAndOneYear() {
        for (int bad : new int[] {0, -1, SlaPolicy.MAX_HOURS + 1}) {
            assertThatThrownBy(() -> policy(bad, null, OverdueAction.NOTIFY_ONLY, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dueAfterHours");
        }
        assertThat(policy(1, null, OverdueAction.NOTIFY_ONLY, null).dueAfterHours()).isEqualTo(1);
        assertThat(policy(SlaPolicy.MAX_HOURS, null, OverdueAction.NOTIFY_ONLY, null).dueAfterHours()).isEqualTo(8760);
    }

    @Test
    void theReminderMustComeStrictlyBeforeTheDeadline() {
        assertThatThrownBy(() -> policy(24, 24, OverdueAction.NOTIFY_ONLY, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(24, 30, OverdueAction.NOTIFY_ONLY, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(24, 0, OverdueAction.NOTIFY_ONLY, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(policy(24, 23, OverdueAction.NOTIFY_ONLY, null).remindBeforeHours()).isEqualTo(23);
    }

    @Test
    void escalatingToAUserNeedsThatUserAndNotifyOnlyForbidsOne() {
        assertThatThrownBy(() -> policy(24, null, OverdueAction.ESCALATE_TO_USER, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs escalateToUser");
        assertThatThrownBy(() -> policy(24, null, OverdueAction.ESCALATE_TO_USER, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(24, null, OverdueAction.NOTIFY_ONLY, "u_x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not take");
        assertThat(policy(24, null, OverdueAction.ESCALATE_TO_USER, " u_x ").escalateToUser()).isEqualTo("u_x");
    }

    @Test
    void escalatingToTheManagerMayNameAFallbackUser() {
        assertThat(policy(24, null, OverdueAction.ESCALATE_TO_MANAGER, null).escalateToUser()).isNull();
        assertThat(policy(24, null, OverdueAction.ESCALATE_TO_MANAGER, "u_cfo").escalateToUser()).isEqualTo("u_cfo");
    }

    @Test
    void aBlankTemplateKeyOrActionIsRefused() {
        assertThatThrownBy(() -> SlaPolicy.of(tenant, " ", 24, null, OverdueAction.NOTIFY_ONLY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlaPolicy.of(tenant, "k", 24, null, null, null)).isInstanceOf(NullPointerException.class);
    }
}
