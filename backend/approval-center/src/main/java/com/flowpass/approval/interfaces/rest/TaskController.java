package com.flowpass.approval.interfaces.rest;

import com.flowpass.approval.application.command.ApproveTaskCommand;
import com.flowpass.approval.application.command.ApproveTaskHandler;
import com.flowpass.approval.application.command.AddApproverCommand;
import com.flowpass.approval.application.command.AddApproverHandler;
import com.flowpass.approval.application.command.RejectTaskCommand;
import com.flowpass.approval.application.command.RejectTaskHandler;
import com.flowpass.approval.application.command.ReturnTaskCommand;
import com.flowpass.approval.application.command.ReturnTaskHandler;
import com.flowpass.approval.application.command.TransferTaskCommand;
import com.flowpass.approval.application.command.TransferTaskHandler;
import com.flowpass.approval.application.port.CurrentActorPort;
import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.application.query.GetTaskHandler;
import com.flowpass.approval.application.query.GetTaskQuery;
import com.flowpass.approval.application.query.ListTasksHandler;
import com.flowpass.approval.application.query.ListTasksQuery;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.contract.dto.ApprovalTaskDTO;
import com.flowpass.approval.contract.dto.AddApproverRequest;
import com.flowpass.approval.contract.dto.ApproveTaskRequest;
import com.flowpass.approval.contract.dto.ReturnTaskRequest;
import com.flowpass.approval.contract.dto.TransferTaskRequest;
import com.flowpass.approval.contract.dto.RejectTaskRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST entry point for approval tasks.
 *
 * <p>No business rules — it builds the command and delegates to the use case. The tenant is read from
 * {@link CurrentTenantPort} (resolved once per request by the inbound filter), never trusted from the
 * body and never re-parsed per endpoint.</p>
 */
@RestController
@RequestMapping("/v1/tasks")
public class TaskController {

    private final ApproveTaskHandler approveHandler;
    private final RejectTaskHandler rejectHandler;
    private final TransferTaskHandler transferHandler;
    private final AddApproverHandler addApproverHandler;
    private final ReturnTaskHandler returnHandler;
    private final GetTaskHandler getTaskHandler;
    private final ListTasksHandler listTasksHandler;
    private final CurrentTenantPort currentTenant;
    private final CurrentActorPort currentActor;

    public TaskController(ApproveTaskHandler approveHandler, RejectTaskHandler rejectHandler,
                          TransferTaskHandler transferHandler, AddApproverHandler addApproverHandler,
                          ReturnTaskHandler returnHandler,
                          GetTaskHandler getTaskHandler, ListTasksHandler listTasksHandler,
                          CurrentTenantPort currentTenant, CurrentActorPort currentActor) {
        this.approveHandler = approveHandler;
        this.rejectHandler = rejectHandler;
        this.transferHandler = transferHandler;
        this.addApproverHandler = addApproverHandler;
        this.returnHandler = returnHandler;
        this.getTaskHandler = getTaskHandler;
        this.listTasksHandler = listTasksHandler;
        this.currentTenant = currentTenant;
        this.currentActor = currentActor;
    }

    @PostMapping("/{id}/approve")
    public ApprovalInstanceDTO approve(
            @PathVariable("id") String taskId,
            @RequestBody @Valid ApproveTaskRequest body) {
        return approveHandler.handle(new ApproveTaskCommand(
                currentTenant.currentTenant(), taskId, currentActor.currentActor(), body.comment()));
    }

    @PostMapping("/{id}/reject")
    public ApprovalInstanceDTO reject(
            @PathVariable("id") String taskId,
            @RequestBody @Valid RejectTaskRequest body) {
        return rejectHandler.handle(new RejectTaskCommand(
                currentTenant.currentTenant(), taskId, currentActor.currentActor(), body.comment()));
    }

    @PostMapping("/{id}/transfer")
    public ApprovalTaskDTO transfer(
            @PathVariable("id") String taskId,
            @RequestBody @Valid TransferTaskRequest body) {
        return transferHandler.handle(new TransferTaskCommand(
                currentTenant.currentTenant(), taskId, currentActor.currentActor(), body.to(), body.comment()));
    }

    /** Add-sign: ask another user to approve this round as well. Returns the task opened for them. */
    @PostMapping("/{id}/add-approver")
    public ApprovalTaskDTO addApprover(
            @PathVariable("id") String taskId,
            @RequestBody @Valid AddApproverRequest body) {
        return addApproverHandler.handle(new AddApproverCommand(
                currentTenant.currentTenant(), taskId, currentActor.currentActor(), body.userId(), body.comment()));
    }

    /** Send the request back to the previous approval round. */
    @PostMapping("/{id}/return")
    public ApprovalInstanceDTO sendBack(
            @PathVariable("id") String taskId,
            @RequestBody @Valid ReturnTaskRequest body) {
        return returnHandler.handle(new ReturnTaskCommand(
                currentTenant.currentTenant(), taskId, currentActor.currentActor(), body.comment()));
    }

    @GetMapping("/{id}")
    public ApprovalTaskDTO get(@PathVariable("id") String taskId) {
        return getTaskHandler.handle(new GetTaskQuery(currentTenant.currentTenant(), taskId));
    }

    @GetMapping
    public List<ApprovalTaskDTO> list(
            @RequestParam(value = "instanceId", required = false) String instanceId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "mine", defaultValue = "false") boolean mine,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletResponse response) {
        List<ApprovalTaskDTO> result = listTasksHandler.handle(
                new ListTasksQuery(currentTenant.currentTenant(), instanceId, status,
                        mine ? currentActor.currentActor() : null, cursor, limit));
        if (!result.isEmpty()) {
            response.setHeader("X-Next-Cursor", result.get(result.size() - 1).id());
        }
        return result;
    }
}
