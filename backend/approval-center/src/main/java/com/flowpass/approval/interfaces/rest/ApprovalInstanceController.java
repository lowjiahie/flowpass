package com.flowpass.approval.interfaces.rest;

import com.flowpass.approval.application.query.GetApprovalInstanceHandler;
import com.flowpass.approval.application.query.GetApprovalInstanceQuery;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.runtime.ApprovalId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for approval instances (read path shown for the skeleton).
 *
 * <p>Contains no business rules — it builds the query and delegates to the use case. The tenant id
 * is resolved from a trusted request attribute/header server-side, never trusted as a raw body
 * value. Until the gateway/keycloak adapter is in place, a default tenant is used for local runs.</p>
 */
@RestController
@RequestMapping("/v1/approval-instances")
public class ApprovalInstanceController {

    private final GetApprovalInstanceHandler handler;

    public ApprovalInstanceController(GetApprovalInstanceHandler handler) {
        this.handler = handler;
    }

    @GetMapping("/{id}")
    public ApprovalInstanceDTO get(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        String tid = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        return handler.handle(new GetApprovalInstanceQuery(new TenantId(tid), new ApprovalId(id)));
    }
}
