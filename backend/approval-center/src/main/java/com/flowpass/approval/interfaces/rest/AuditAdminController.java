package com.flowpass.approval.interfaces.rest;

import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.application.query.ListAuditRecordsHandler;
import com.flowpass.approval.application.query.ListAuditRecordsQuery;
import com.flowpass.approval.contract.dto.AuditRecordDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin read endpoint for the audit ledger (approval action timeline).
 *
 * <p>Lists immutable audit entries for a tenant, optionally filtered by instance id and action, paged
 * by id ascending. The tenant comes from {@link CurrentTenantPort} (resolved once per request by the
 * inbound filter), consistent with the rest of the read endpoints.</p>
 */
@RestController
@RequestMapping("/v1/admin/audit-records")
public class AuditAdminController {

    private final ListAuditRecordsHandler listHandler;
    private final CurrentTenantPort currentTenant;

    public AuditAdminController(ListAuditRecordsHandler listHandler, CurrentTenantPort currentTenant) {
        this.listHandler = listHandler;
        this.currentTenant = currentTenant;
    }

    @GetMapping
    public List<AuditRecordDTO> list(
            @RequestParam(value = "instanceId", required = false) String instanceId,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletResponse response) {
        AuditAction actionEnum = (action == null || action.isBlank()) ? null : AuditAction.valueOf(action);
        List<AuditRecordDTO> result = listHandler.handle(
                new ListAuditRecordsQuery(currentTenant.currentTenant(), instanceId, actionEnum, cursor, limit));
        if (!result.isEmpty()) {
            response.setHeader("X-Next-Cursor", result.get(result.size() - 1).id());
        }
        return result;
    }
}
