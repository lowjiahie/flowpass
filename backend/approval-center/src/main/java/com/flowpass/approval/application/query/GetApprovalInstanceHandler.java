package com.flowpass.approval.application.query;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Read use case: fetch a single approval instance, tenant-scoped.
 *
 * <p>Focused on one responsibility — load the aggregate through the port and map it to the public
 * contract DTO. No business rules here; those stay in the aggregate. The read path is not a place
 * to put authorization: authorization is enforced at the write handlers / entry point.</p>
 */
@Service
public class GetApprovalInstanceHandler {

    private final ApprovalInstanceRepository repository;

    public GetApprovalInstanceHandler(ApprovalInstanceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ApprovalInstanceDTO handle(GetApprovalInstanceQuery query) {
        ApprovalInstance instance = repository
                .findById(query.tenantId(), query.instanceId())
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.INSTANCE_NOT_FOUND,
                        "Approval instance not found: " + query.instanceId().value()));

        return new ApprovalInstanceDTO(
                instance.id().value(),
                instance.businessKey(),
                instance.templateKey(),
                instance.status().name(),
                instance.initiator().type(),
                instance.initiator().id(),
                instance.money() == null ? null : instance.money().amount(),
                instance.money() == null ? null : instance.money().currency(),
                instance.definitionVersion(),
                Instant.now().toString()
        );
    }
}
