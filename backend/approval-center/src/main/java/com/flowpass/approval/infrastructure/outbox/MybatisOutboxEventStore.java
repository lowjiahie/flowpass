package com.flowpass.approval.infrastructure.outbox;

import com.flowpass.approval.application.port.OutboxEventStore;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.infrastructure.persistence.OutboxEventRow;
import com.flowpass.approval.infrastructure.persistence.OutboxMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Adapter implementing the {@link OutboxEventStore} port on top of MyBatis.
 *
 * <p>Reads {@code PENDING} event rows (oldest first) and flips them to {@code DISPATCHED} so the
 * dispatcher picks each up exactly once.</p>
 */
@Repository
public class MybatisOutboxEventStore implements OutboxEventStore {

    private final OutboxMapper mapper;

    public MybatisOutboxEventStore(OutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<PendingOutboxEvent> findPending(int limit) {
        return mapper.findPending(limit).stream()
                .map(this::toPending)
                .toList();
    }

    @Override
    public void markDispatched(String eventId) {
        mapper.markDispatched(eventId);
    }

    @Override
    public String findPayload(String eventId) {
        return mapper.findPayload(eventId);
    }

    private PendingOutboxEvent toPending(OutboxEventRow row) {
        return new PendingOutboxEvent(row.getEventId(), new TenantId(row.getTenantId()), row.getType(), row.getPayload());
    }
}
