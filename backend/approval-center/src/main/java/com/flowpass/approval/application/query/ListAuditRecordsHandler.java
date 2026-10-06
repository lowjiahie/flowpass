package com.flowpass.approval.application.query;

import com.flowpass.approval.contract.dto.AuditRecordDTO;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read use case: page audit entries (approval action timeline) for a tenant, optionally filtered by
 * instance id and action.
 *
 * <p>Read-only mapping to the public contract DTO; no business rules here. The ledger is append-only
 * and immutable, so this is a pure {@code ORDER BY id ASC / id > cursor} projection.</p>
 */
@Service
public class ListAuditRecordsHandler {

    private final AuditRecordRepository auditRecordRepository;

    public ListAuditRecordsHandler(AuditRecordRepository auditRecordRepository) {
        this.auditRecordRepository = auditRecordRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_AUDIT + "')")
    @Transactional(readOnly = true)
    public List<AuditRecordDTO> handle(ListAuditRecordsQuery query) {
        return auditRecordRepository.findPage(query.tenantId(), query.instanceId(), query.action(),
                        query.cursor(), query.limit())
                .stream()
                .map(this::toDto)
                .toList();
    }

    private AuditRecordDTO toDto(AuditRecord record) {
        return new AuditRecordDTO(
                record.id(),
                record.tenantId().value(),
                record.instanceId(),
                record.taskId(),
                record.actor().type(),
                record.actor().id(),
                record.action().name(),
                record.comment(),
                record.occurredAt());
    }
}
