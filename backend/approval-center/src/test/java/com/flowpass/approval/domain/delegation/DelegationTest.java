package com.flowpass.approval.domain.delegation;

import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationTest {

    private final TenantId tenant = new TenantId("t_1");
    private final Instant now = Instant.parse("2026-06-01T09:00:00Z");

    private Delegation create(Instant from, Instant to) {
        return Delegation.create(tenant, "u_a", "u_b", from, to, now);
    }

    @Test
    void aDelegationAppliesFromItsStartUntilItsEndExclusive() {
        Delegation d = create(now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(3)));

        assertThat(d.id()).startsWith("dlg_");
        assertThat(d.isActiveAt(now)).isFalse();
        assertThat(d.isActiveAt(now.plus(Duration.ofDays(1)))).isTrue();
        assertThat(d.isActiveAt(now.plus(Duration.ofDays(2)))).isTrue();
        assertThat(d.isActiveAt(now.plus(Duration.ofDays(3)))).as("the end is exclusive").isFalse();
    }

    @Test
    void noStartOrAStartInThePastMeansFromNow() {
        assertThat(create(null, now.plus(Duration.ofDays(2))).startsAt()).isEqualTo(now);
        assertThat(create(now.minus(Duration.ofDays(5)), now.plus(Duration.ofDays(2))).startsAt()).isEqualTo(now);
        assertThat(create(null, now.plus(Duration.ofDays(2))).isActiveAt(now)).isTrue();
    }

    @Test
    void itMustEndInTheFutureAndAfterItStarts() {
        assertThatThrownBy(() -> create(null, now)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end in the future");
        assertThatThrownBy(() -> create(null, now.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(2))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("end after it starts");
    }

    @Test
    void itCanLastAtMost180Days() {
        assertThat(create(null, now.plus(Duration.ofDays(Delegation.MAX_DAYS))).endsAt()).isNotNull();
        assertThatThrownBy(() -> create(null, now.plus(Duration.ofDays(Delegation.MAX_DAYS)).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 180 days");
    }

    @Test
    void youCannotDelegateToYourselfOrToNobody() {
        assertThatThrownBy(() -> Delegation.create(tenant, "u_a", "u_a", null, now.plusSeconds(60), now))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("yourself");
        assertThatThrownBy(() -> Delegation.create(tenant, "u_a", " ", null, now.plusSeconds(60), now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Delegation.create(tenant, "", "u_b", null, now.plusSeconds(60), now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Delegation.create(tenant, "u_a", "x".repeat(129), null, now.plusSeconds(60), now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsAreTrimmed() {
        Delegation d = Delegation.create(tenant, " u_a ", " u_b ", null, now.plusSeconds(60), now);

        assertThat(d.delegatorId()).isEqualTo("u_a");
        assertThat(d.delegateId()).isEqualTo("u_b");
    }

    @Test
    void overlapIsDetectedOnlyForPeriodsThatShareTime() {
        Delegation d = create(now.plus(Duration.ofDays(2)), now.plus(Duration.ofDays(4)));

        assertThat(d.overlaps(now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(5)))).isTrue();
        assertThat(d.overlaps(now, now.plus(Duration.ofDays(3)))).isTrue();
        assertThat(d.overlaps(now, now.plus(Duration.ofDays(2)))).as("touching is not overlapping").isFalse();
        assertThat(d.overlaps(now.plus(Duration.ofDays(4)), now.plus(Duration.ofDays(6)))).isFalse();
    }

    @Test
    void aRevokedDelegationNeverAppliesAndNeverOverlaps() {
        Delegation d = create(null, now.plus(Duration.ofDays(2)));

        d.revoke(now.plusSeconds(10));

        assertThat(d.isActiveAt(now.plusSeconds(20))).isFalse();
        assertThat(d.overlaps(now, now.plus(Duration.ofDays(2)))).isFalse();
        assertThat(d.revokedAt()).isEqualTo(now.plusSeconds(10));
        assertThatThrownBy(() -> d.revoke(now.plusSeconds(30))).isInstanceOf(IllegalStateException.class);
    }
}
