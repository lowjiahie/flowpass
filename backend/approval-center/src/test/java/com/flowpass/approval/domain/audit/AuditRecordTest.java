package com.flowpass.approval.domain.audit;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** Unit tests for the {@link AuditRecord} aggregate — immutability and required-field invariants. */
class AuditRecordTest {

    private final TenantId tenant = new TenantId("t_10086");
    private final ApproverRef actor = new ApproverRef("USER", "u_9");

    @Test
    void createsRecordWithAllFields() {
        Instant at = Instant.parse("2026-09-03T08:00:00Z");

        AuditRecord record = AuditRecord.of(
                tenant, "appr_1", "task_1", actor, AuditAction.APPROVED, "looks good", at);

        assertThat(record.id()).startsWith("aud_");
        assertThat(record.tenantId()).isEqualTo(tenant);
        assertThat(record.instanceId()).isEqualTo("appr_1");
        assertThat(record.taskId()).isEqualTo("task_1");
        assertThat(record.actor()).isEqualTo(actor);
        assertThat(record.action()).isEqualTo(AuditAction.APPROVED);
        assertThat(record.comment()).isEqualTo("looks good");
        assertThat(record.occurredAt()).isEqualTo(at);
    }

    @Test
    void allowsNullTaskIdAndComment() {
        AuditRecord record = AuditRecord.of(
                tenant, "appr_1", null, actor, AuditAction.STARTED, null, Instant.now());

        assertThat(record.taskId()).isNull();
        assertThat(record.comment()).isNull();
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThatNullPointerException()
                .isThrownBy(() -> AuditRecord.of(null, "appr_1", null, actor, AuditAction.STARTED, null, Instant.now()));
        assertThatNullPointerException()
                .isThrownBy(() -> AuditRecord.of(tenant, null, null, actor, AuditAction.STARTED, null, Instant.now()));
        assertThatNullPointerException()
                .isThrownBy(() -> AuditRecord.of(tenant, "appr_1", null, null, AuditAction.STARTED, null, Instant.now()));
        assertThatNullPointerException()
                .isThrownBy(() -> AuditRecord.of(tenant, "appr_1", null, actor, null, null, Instant.now()));
        assertThatNullPointerException()
                .isThrownBy(() -> AuditRecord.of(tenant, "appr_1", null, actor, AuditAction.STARTED, null, null));
    }
}
