package com.flowpass.approval.domain.runtime;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.Money;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.event.ApprovalInstanceApproved;
import com.flowpass.approval.domain.event.ApprovalInstanceCreated;
import com.flowpass.approval.domain.event.ApprovalInstanceRejected;
import com.flowpass.approval.domain.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Aggregate invariant tests for {@link ApprovalInstance} — happy path plus terminal-state protection.
 */
class ApprovalInstanceTest {

    private final TenantId tenant = new TenantId("t_10086");
    private final ApproverRef actor = new ApproverRef("USER", "u_1");

    private ApprovalInstance start() {
        return ApprovalInstance.start(
                tenant,
                "app_procurement",
                "PO-2026-0001",
                "purchase_request",
                actor,
                new Money(new BigDecimal("23500.00"), "MYR"),
                3,
                Map.of("amount", new BigDecimal("23500.00"), "currency", "MYR"));
    }

    @Test
    void startsInRunningAndEmitsCreatedEvent() {
        ApprovalInstance instance = start();
        List<DomainEvent> events = instance.drainEvents();

        assertThat(instance.status()).isEqualTo(ApprovalStatus.RUNNING);
        assertThat(instance.version()).isZero();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ApprovalInstanceCreated.class);
    }

    @Test
    void onlyTheInitiatorIsRecognisedAsInitiator() {
        ApprovalInstance instance = start();

        assertThat(instance.isInitiatedBy(new ApproverRef("USER", "u_1"))).isTrue();
        assertThat(instance.isInitiatedBy(new ApproverRef("USER", "u_2"))).isFalse();
        assertThat(instance.isInitiatedBy(new ApproverRef("SYSTEM", "u_1"))).isFalse();
    }

    @Test
    void approveTransitionsToApprovedAndBumpsVersion() {
        ApprovalInstance instance = start();
        instance.drainEvents(); // discard the create event

        instance.approve(actor);
        List<DomainEvent> events = instance.drainEvents();

        assertThat(instance.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(instance.version()).isEqualTo(1L);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ApprovalInstanceApproved.class);
    }

    @Test
    void rejectTransitionsToRejected() {
        ApprovalInstance instance = start();
        instance.drainEvents(); // discard the create event

        instance.reject(actor);

        assertThat(instance.status()).isEqualTo(ApprovalStatus.REJECTED);
        List<DomainEvent> events = instance.drainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ApprovalInstanceRejected.class);
    }

    @Test
    void terminalStateCannotBeMutated() {
        ApprovalInstance instance = start();
        instance.approve(actor);

        assertThatThrownBy(() -> instance.reject(actor))
                .isInstanceOf(IllegalStateException.class);
        assertThat(instance.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void moneyRejectsMoreThanTwoDecimals() {
        assertThatThrownBy(() -> new Money(new BigDecimal("1.234"), "MYR"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
