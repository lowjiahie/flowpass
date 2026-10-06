package com.flowpass.approval.infrastructure.idempotency;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.IdempotencyPort;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.infrastructure.persistence.IdempotencyMapper;
import com.flowpass.approval.infrastructure.persistence.IdempotencyRecordRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Adapter implementing the {@link IdempotencyPort} on top of {@code idempotency_record}.
 *
 * <p>Claims a tenant-scoped request key by inserting a row; a duplicate key surfaces as a
 * {@link DuplicateKeyException} and is translated to an idempotency-conflict business error. The call
 * happens inside the same transaction as the business write, so a failed write rolls the claim back.</p>
 */
@Repository
public class MybatisIdempotencyPort implements IdempotencyPort {

    private final IdempotencyMapper mapper;

    public MybatisIdempotencyPort(IdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void claim(TenantId tenantId, String applicationId, String idemKey, String responseRef) {
        IdempotencyRecordRow row = new IdempotencyRecordRow();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantId.value());
        row.setApplicationId(applicationId);
        row.setIdemKey(idemKey);
        row.setResponseRef(responseRef);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(BusinessErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency key already used: " + idemKey);
        }
    }
}
