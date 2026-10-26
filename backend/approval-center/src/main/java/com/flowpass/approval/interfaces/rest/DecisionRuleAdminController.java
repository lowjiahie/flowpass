package com.flowpass.approval.interfaces.rest;

import com.flowpass.approval.application.command.CreateDecisionRuleCommand;
import com.flowpass.approval.application.command.CreateDecisionRuleHandler;
import com.flowpass.approval.application.port.CurrentActorPort;
import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.application.query.ListDecisionRulesHandler;
import com.flowpass.approval.application.query.ListDecisionRulesQuery;
import com.flowpass.approval.application.query.PreviewApproversHandler;
import com.flowpass.approval.application.query.PreviewApproversQuery;
import com.flowpass.approval.contract.dto.ApproverDTO;
import com.flowpass.approval.contract.dto.ApproverPreviewDTO;
import com.flowpass.approval.contract.dto.CreateDecisionRuleRequest;
import com.flowpass.approval.contract.dto.DecisionRuleDTO;
import com.flowpass.approval.contract.dto.PreviewApproversRequest;
import com.flowpass.approval.domain.common.ApproverRef;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin endpoints for the approval matrix: define and list rules.
 *
 * <p>Contains no business rules — it builds the command/query and delegates to the use case. The tenant
 * is read from {@link CurrentTenantPort} (resolved once per request by the inbound filter), never taken
 * from a raw body value and never re-parsed per endpoint.</p>
 */
@RestController
@RequestMapping("/v1/admin/decision-rules")
public class DecisionRuleAdminController {

    private static final int DEFAULT_PRIORITY = 100;

    private final CreateDecisionRuleHandler createHandler;
    private final ListDecisionRulesHandler listHandler;
    private final PreviewApproversHandler previewHandler;
    private final CurrentTenantPort currentTenant;
    private final CurrentActorPort currentActor;

    public DecisionRuleAdminController(
            CreateDecisionRuleHandler createHandler,
            ListDecisionRulesHandler listHandler,
            PreviewApproversHandler previewHandler,
            CurrentTenantPort currentTenant,
            CurrentActorPort currentActor) {
        this.createHandler = createHandler;
        this.listHandler = listHandler;
        this.previewHandler = previewHandler;
        this.currentTenant = currentTenant;
        this.currentActor = currentActor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionRuleDTO create(@RequestBody @Valid CreateDecisionRuleRequest body) {
        CreateDecisionRuleCommand cmd = new CreateDecisionRuleCommand(
                currentTenant.currentTenant(),
                body.templateKey(),
                body.priority() == null ? DEFAULT_PRIORITY : body.priority(),
                body.amountMin(),
                body.amountMax(),
                body.fieldMatches(),
                body.expression(),
                body.approvers().stream().map(a -> new ApproverRef(a.type(), a.id())).toList(),
                body.approvers().stream().map(ApproverDTO::round).toList());
        return createHandler.handle(cmd);
    }

    @GetMapping
    public List<DecisionRuleDTO> list(@RequestParam(value = "templateKey", required = false) String templateKey) {
        return listHandler.handle(new ListDecisionRulesQuery(currentTenant.currentTenant(), templateKey));
    }

    /** Dry run: which approvers these variables would resolve to (same resolution as a real start). */
    @PostMapping("/preview")
    public ApproverPreviewDTO preview(@RequestBody @Valid PreviewApproversRequest body) {
        ApproverRef initiator = (body.initiatorId() == null || body.initiatorId().isBlank())
                ? currentActor.currentActor()
                : new ApproverRef("USER", body.initiatorId());
        return previewHandler.handle(new PreviewApproversQuery(
                currentTenant.currentTenant(), body.templateKey(), initiator, body.variables()));
    }
}
