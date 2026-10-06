package com.flowpass.approval.infrastructure.persistence;

import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Adapter implementing the {@link AuditRecordRepository} port on top of MyBatis.
 *
 * <p>Maps the aggregate to the append-only {@code approval_action} row. Only insert is supported —
 * there is no update or delete, mirroring the immutable nature of an audit fact.</p>
 */
@Repository
public class MybatisAuditRecordRepository implements AuditRecordRepository {

    private final AuditRecordMapper mapper;

    public MybatisAuditRecordRepository(AuditRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(AuditRecord record) {
        mapper.insert(toRow(record));
    }

    @Override
    public List<AuditRecord> findPage(TenantId tenantId, String instanceId, AuditAction action,
                                      String cursor, int limit) {
        return mapper.findPage(tenantId.value(), instanceId,
                        action == null ? null : action.name(), cursor, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    private AuditRecord toDomain(AuditRecordRow row) {
        return AuditRecord.hydrate(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getInstanceId(),
                row.getTaskId(),
                new ApproverRef(row.getActorType(), row.getActorId()),
                AuditAction.valueOf(row.getAction()),
                row.getComment(),
                row.getCreatedAt());
    }

    private AuditRecordRow toRow(AuditRecord record) {
        AuditRecordRow row = new AuditRecordRow();
        row.setId(record.id());
        row.setTenantId(record.tenantId().value());
        row.setInstanceId(record.instanceId());
        row.setTaskId(record.taskId());
        row.setActorType(record.actor().type());
        row.setActorId(record.actor().id());
        row.setAction(record.action().name());
        row.setComment(record.comment());
        row.setCreatedAt(record.occurredAt());
        return row;
    }
}
