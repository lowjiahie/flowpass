package com.flowpass.approval.application.query;

import com.flowpass.approval.contract.dto.AuditRecordDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ListAuditRecordsHandler} — paging, filtering and DTO mapping. Port is mocked. */
@ExtendWith(MockitoExtension.class)
class ListAuditRecordsHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");
    private final Instant occurredAt = Instant.parse("2026-09-03T08:00:00Z");

    @Mock
    private AuditRecordRepository auditRecordRepository;

    private ListAuditRecordsHandler handler() {
        return new ListAuditRecordsHandler(auditRecordRepository);
    }

    @Test
    void mapsTimelineFilteredByInstanceAndAction() {
        AuditRecord record = AuditRecord.hydrate(
                "aud_1", tenant, "appr_1", "task_1",
                new ApproverRef("USER", "u_9"), AuditAction.APPROVED, "ok", occurredAt);
        when(auditRecordRepository.findPage(eq(tenant), eq("appr_1"), eq(AuditAction.APPROVED), isNull(), eq(20)))
                .thenReturn(List.of(record));

        List<AuditRecordDTO> result = handler().handle(
                new ListAuditRecordsQuery(tenant, "appr_1", AuditAction.APPROVED, null, 20));

        assertThat(result).hasSize(1);
        AuditRecordDTO dto = result.get(0);
        assertThat(dto.id()).isEqualTo("aud_1");
        assertThat(dto.tenantId()).isEqualTo("t_10086");
        assertThat(dto.instanceId()).isEqualTo("appr_1");
        assertThat(dto.taskId()).isEqualTo("task_1");
        assertThat(dto.actorType()).isEqualTo("USER");
        assertThat(dto.actorId()).isEqualTo("u_9");
        assertThat(dto.action()).isEqualTo("APPROVED");
        assertThat(dto.comment()).isEqualTo("ok");
        assertThat(dto.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void passesNullsWhenFiltersAbsent() {
        when(auditRecordRepository.findPage(eq(tenant), isNull(), isNull(), isNull(), eq(20)))
                .thenReturn(List.of());

        List<AuditRecordDTO> result = handler().handle(
                new ListAuditRecordsQuery(tenant, null, null, null, 20));

        assertThat(result).isEmpty();
    }
}
