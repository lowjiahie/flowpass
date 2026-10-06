package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis mapper for {@code idempotency_record}. The unique index on {@code (tenant, application, key)}
 * turns a duplicate write request into a unique-constraint violation, which the adapter maps to a
 * conflict business error.
 */
@Mapper
public interface IdempotencyMapper {

    @Insert("""
            INSERT INTO idempotency_record
                (id, tenant_id, application_id, idem_key, response_ref, created_at)
            VALUES
                (#{id}, #{tenantId}, #{applicationId}, #{idemKey}, #{responseRef}, now())
            """)
    int insert(IdempotencyRecordRow row);
}
