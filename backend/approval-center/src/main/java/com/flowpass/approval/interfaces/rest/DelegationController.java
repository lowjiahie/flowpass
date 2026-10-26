package com.flowpass.approval.interfaces.rest;

import com.flowpass.approval.application.command.DelegationHandlers;
import com.flowpass.approval.application.port.CurrentActorPort;
import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.contract.dto.CreateDelegationRequest;
import com.flowpass.approval.contract.dto.DelegationDTO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "Delegate my new tasks to somebody else while I am away." Always about the caller's own delegations — the
 * delegator comes from the credentials, never from the body. No business rules here.
 */
@RestController
@RequestMapping("/v1/delegations")
public class DelegationController {

    private final DelegationHandlers handlers;
    private final CurrentTenantPort currentTenant;
    private final CurrentActorPort currentActor;

    public DelegationController(DelegationHandlers handlers, CurrentTenantPort currentTenant,
                                CurrentActorPort currentActor) {
        this.handlers = handlers;
        this.currentTenant = currentTenant;
        this.currentActor = currentActor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DelegationDTO create(@RequestBody @Valid CreateDelegationRequest body) {
        return handlers.create(currentTenant.currentTenant(), currentActor.currentActor(), body.delegateId(),
                body.startsAt(), body.endsAt());
    }

    @GetMapping
    public List<DelegationDTO> mine() {
        return handlers.mine(currentTenant.currentTenant(), currentActor.currentActor());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable("id") String id) {
        handlers.revoke(currentTenant.currentTenant(), currentActor.currentActor(), id);
    }
}
