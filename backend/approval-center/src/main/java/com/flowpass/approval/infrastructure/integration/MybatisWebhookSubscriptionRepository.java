package com.flowpass.approval.infrastructure.integration;

import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.integration.WebhookSubscription;
import com.flowpass.approval.domain.integration.WebhookSubscriptionRepository;
import com.flowpass.approval.infrastructure.persistence.WebhookSubscriptionMapper;
import com.flowpass.approval.infrastructure.persistence.WebhookSubscriptionRow;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Adapter implementing the {@link WebhookSubscriptionRepository} port on top of MyBatis.
 *
 * <p>Hides row/relational concerns from the domain: it converts between the persistence row and the
 * {@code WebhookSubscription} value. Only enabled subscriptions are resolved.</p>
 */
@Repository
public class MybatisWebhookSubscriptionRepository implements WebhookSubscriptionRepository {

    private final WebhookSubscriptionMapper mapper;

    public MybatisWebhookSubscriptionRepository(WebhookSubscriptionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<WebhookSubscription> findEnabled(TenantId tenantId, String eventType) {
        return mapper.findEnabled(tenantId.value(), eventType).stream()
                .map(this::toDomain)
                .toList();
    }

    private WebhookSubscription toDomain(WebhookSubscriptionRow row) {
        return new WebhookSubscription(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getEventType(),
                row.getEndpoint(),
                row.getEnabled());
    }
}
