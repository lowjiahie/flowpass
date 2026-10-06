package com.flowpass.approval.application.query;

import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Read use case: page approval instances for a tenant using id-based cursor pagination.
 *
 * <p>Loads the aggregates through the port and maps them to the public DTO. Ordering is by id ascending;
 * the caller passes the last seen id as {@code cursor} to get the next page. No business rules live
 * here.</p>
 */
@Service
public class ListApprovalInstancesHandler {

    private final ApprovalInstanceRepository repository;

    public ListApprovalInstancesHandler(ApprovalInstanceRepository repository) {
        this.repository = repository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_READ + "')")
    @Transactional(readOnly = true)
    public List<ApprovalInstanceDTO> handle(ListApprovalInstancesQuery query) {
        return repository.findPage(query.tenantId(), query.status(), query.cursor(), query.limit())
                .stream()
                .map(this::toDto)
                .toList();
    }

    private ApprovalInstanceDTO toDto(ApprovalInstance instance) {
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
