package com.flowpass.approval.interfaces.rest;

import com.flowpass.approval.application.command.StartApprovalCommand;
import com.flowpass.approval.application.command.StartApprovalHandler;
import com.flowpass.approval.application.command.WithdrawInstanceCommand;
import com.flowpass.approval.application.command.WithdrawInstanceHandler;
import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.port.CurrentActorPort;
import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.application.query.GetApprovalInstanceHandler;
import com.flowpass.approval.application.query.GetApprovalInstanceQuery;
import com.flowpass.approval.application.query.ListApprovalInstancesHandler;
import com.flowpass.approval.application.query.ListApprovalInstancesQuery;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.contract.dto.CreateApprovalRequest;
import com.flowpass.approval.contract.dto.WithdrawInstanceRequest;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.runtime.ApprovalId;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST entry point for approval instances (read path shown for the skeleton).
 *
 * <p>Contains no business rules — it builds the query and delegates to the use case. The tenant is read
 * from {@link CurrentTenantPort} (resolved once per request by the inbound filter), never trusted from a
 * raw body value and never re-parsed per endpoint.</p>
 */
@RestController
@RequestMapping("/v1/approval-instances")
public class ApprovalInstanceController {

    private final StartApprovalHandler startHandler;
    private final GetApprovalInstanceHandler handler;
    private final ListApprovalInstancesHandler listHandler;
    private final WithdrawInstanceHandler withdrawHandler;
    private final CurrentTenantPort currentTenant;
    private final CurrentActorPort currentActor;

    public ApprovalInstanceController(
            StartApprovalHandler startHandler,
            GetApprovalInstanceHandler handler,
            ListApprovalInstancesHandler listHandler,
            WithdrawInstanceHandler withdrawHandler,
            CurrentTenantPort currentTenant,
            CurrentActorPort currentActor) {
        this.startHandler = startHandler;
        this.handler = handler;
        this.listHandler = listHandler;
        this.withdrawHandler = withdrawHandler;
        this.currentTenant = currentTenant;
        this.currentActor = currentActor;
    }

    @GetMapping("/{id}")
    public ApprovalInstanceDTO get(@PathVariable("id") String id) {
        return handler.handle(new GetApprovalInstanceQuery(currentTenant.currentTenant(), new ApprovalId(id)));
    }


    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalInstanceDTO create(
            @RequestHeader(value = "X-Application-Id", required = false) String applicationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody @Valid CreateApprovalRequest body) {
        String appId = (applicationId == null || applicationId.isBlank()) ? "default" : applicationId;
        StartApprovalCommand cmd = new StartApprovalCommand(
                currentTenant.currentTenant(),
                appId,
                body.templateKey(),
                body.externalBusinessKey(),
                idempotencyKey,
                currentActor.currentActor(),
                onBehalfOf(body),
                body.variables(),
                body.callbackContext());
        return startHandler.handle(cmd);
    }

    @PostMapping("/{id}/withdraw")
    public ApprovalInstanceDTO withdraw(
            @PathVariable("id") String id,
            @RequestBody @Valid WithdrawInstanceRequest body) {
        return withdrawHandler.handle(new WithdrawInstanceCommand(
                currentTenant.currentTenant(), id, currentActor.currentActor(), body.comment()));
    }

    @GetMapping
    public List<ApprovalInstanceDTO> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletResponse response) {
        List<ApprovalInstanceDTO> result = listHandler.handle(
                new ListApprovalInstancesQuery(currentTenant.currentTenant(), status, cursor, limit));
        if (!result.isEmpty()) {
            response.setHeader("X-Next-Cursor", result.get(result.size() - 1).id());
        }
        return result;
    }

    /** Optional "start on behalf of" principal; both fields or neither. Authorization is the handler's job. */
    private static ApproverRef onBehalfOf(CreateApprovalRequest body) {
        boolean hasType = body.initiatorType() != null && !body.initiatorType().isBlank();
        boolean hasId = body.initiatorId() != null && !body.initiatorId().isBlank();
        if (hasType != hasId) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED,
                    "initiatorType and initiatorId must be provided together");
        }
        return hasType ? new ApproverRef(body.initiatorType(), body.initiatorId()) : null;
    }
}
