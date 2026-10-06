package com.flowpass.approval.interfaces.rest;

import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.application.query.ListWebhookDeliveriesHandler;
import com.flowpass.approval.application.query.ListWebhookDeliveriesQuery;
import com.flowpass.approval.contract.dto.WebhookDeliveryDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin read endpoint for the webhook delivery ledger (ops view).
 *
 * <p>Lists deliveries for a tenant, optionally filtered by status, paged by id ascending. The tenant
 * comes from {@link CurrentTenantPort} (resolved once per request by the inbound filter), consistent
 * with the rest of the read endpoints.</p>
 */
@RestController
@RequestMapping("/v1/admin/webhook-deliveries")
public class WebhookAdminController {

    private final ListWebhookDeliveriesHandler listHandler;
    private final CurrentTenantPort currentTenant;

    public WebhookAdminController(ListWebhookDeliveriesHandler listHandler, CurrentTenantPort currentTenant) {
        this.listHandler = listHandler;
        this.currentTenant = currentTenant;
    }

    @GetMapping
    public List<WebhookDeliveryDTO> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletResponse response) {
        List<WebhookDeliveryDTO> result = listHandler.handle(
                new ListWebhookDeliveriesQuery(currentTenant.currentTenant(), status, cursor, limit));
        if (!result.isEmpty()) {
            response.setHeader("X-Next-Cursor", result.get(result.size() - 1).id());
        }
        return result;
    }
}
