package com.flowpass.approval.interfaces.rest;

import com.flowpass.approval.application.command.CreateTemplateCommand;
import com.flowpass.approval.application.command.CreateTemplateHandler;
import com.flowpass.approval.application.command.PublishTemplateCommand;
import com.flowpass.approval.application.command.PublishTemplateHandler;
import com.flowpass.approval.application.command.SaveSlaPolicyCommand;
import com.flowpass.approval.application.command.SaveTemplateDraftCommand;
import com.flowpass.approval.application.command.SaveTemplateDraftHandler;
import com.flowpass.approval.application.command.SaveTemplateFormSchemaCommand;
import com.flowpass.approval.application.command.SaveTemplateFormSchemaHandler;
import com.flowpass.approval.application.command.SetTemplateEnabledCommand;
import com.flowpass.approval.application.command.SlaPolicyHandlers;
import com.flowpass.approval.application.command.SetTemplateEnabledHandler;
import com.flowpass.approval.application.port.CurrentActorPort;
import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.application.query.GetTemplateHandler;
import com.flowpass.approval.application.query.GetTemplateQuery;
import com.flowpass.approval.application.query.GetTemplateVersionHandler;
import com.flowpass.approval.application.query.GetTemplateVersionQuery;
import com.flowpass.approval.application.query.ListTemplateVersionsHandler;
import com.flowpass.approval.application.query.ListTemplateVersionsQuery;
import com.flowpass.approval.application.query.ListTemplatesHandler;
import com.flowpass.approval.application.query.ListTemplatesQuery;
import com.flowpass.approval.contract.dto.CreateTemplateRequest;
import com.flowpass.approval.contract.dto.SaveSlaPolicyRequest;
import com.flowpass.approval.contract.dto.SaveTemplateDraftRequest;
import com.flowpass.approval.contract.dto.SaveTemplateFormSchemaRequest;
import com.flowpass.approval.contract.dto.SlaPolicyDTO;
import com.flowpass.approval.contract.dto.TemplateDTO;
import com.flowpass.approval.contract.dto.TemplateDetailDTO;
import com.flowpass.approval.contract.dto.TemplateVersionDTO;
import com.flowpass.approval.contract.dto.TemplateVersionDetailDTO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin endpoints for the template lifecycle: register, edit the draft, publish a new immutable version,
 * disable / enable, and read templates and their versions.
 *
 * <p>Contains no business rules — it builds the command/query and delegates to the use case. The tenant
 * is read from {@link CurrentTenantPort} and the publisher from {@link CurrentActorPort} (resolved from the
 * authenticated request), never taken from a body.</p>
 */
@RestController
@RequestMapping("/v1/admin/templates")
public class TemplateAdminController {

    private final CreateTemplateHandler createHandler;
    private final SaveTemplateDraftHandler saveDraftHandler;
    private final SlaPolicyHandlers slaHandlers;
    private final SaveTemplateFormSchemaHandler formSchemaHandler;
    private final PublishTemplateHandler publishHandler;
    private final SetTemplateEnabledHandler enabledHandler;
    private final ListTemplatesHandler listHandler;
    private final GetTemplateHandler getHandler;
    private final ListTemplateVersionsHandler versionsHandler;
    private final GetTemplateVersionHandler versionHandler;
    private final CurrentTenantPort currentTenant;
    private final CurrentActorPort currentActor;

    public TemplateAdminController(
            CreateTemplateHandler createHandler,
            SaveTemplateDraftHandler saveDraftHandler,
            SlaPolicyHandlers slaHandlers,
            SaveTemplateFormSchemaHandler formSchemaHandler,
            PublishTemplateHandler publishHandler,
            SetTemplateEnabledHandler enabledHandler,
            ListTemplatesHandler listHandler,
            GetTemplateHandler getHandler,
            ListTemplateVersionsHandler versionsHandler,
            GetTemplateVersionHandler versionHandler,
            CurrentTenantPort currentTenant,
            CurrentActorPort currentActor) {
        this.createHandler = createHandler;
        this.saveDraftHandler = saveDraftHandler;
        this.slaHandlers = slaHandlers;
        this.formSchemaHandler = formSchemaHandler;
        this.publishHandler = publishHandler;
        this.enabledHandler = enabledHandler;
        this.listHandler = listHandler;
        this.getHandler = getHandler;
        this.versionsHandler = versionsHandler;
        this.versionHandler = versionHandler;
        this.currentTenant = currentTenant;
        this.currentActor = currentActor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateDTO create(@RequestBody @Valid CreateTemplateRequest body) {
        return createHandler.handle(new CreateTemplateCommand(currentTenant.currentTenant(), body.templateKey()));
    }

    @GetMapping
    public List<TemplateDTO> list() {
        return listHandler.handle(new ListTemplatesQuery(currentTenant.currentTenant()));
    }

    @GetMapping("/{templateKey}")
    public TemplateDetailDTO get(@PathVariable("templateKey") String templateKey) {
        return getHandler.handle(new GetTemplateQuery(currentTenant.currentTenant(), templateKey));
    }

    @PutMapping("/{templateKey}/draft")
    public TemplateDTO saveDraft(
            @PathVariable("templateKey") String templateKey,
            @RequestBody @Valid SaveTemplateDraftRequest body) {
        return saveDraftHandler.handle(
                new SaveTemplateDraftCommand(currentTenant.currentTenant(), templateKey, body.bpmnXml()));
    }

    @PutMapping("/{templateKey}/draft/form-schema")
    public TemplateDTO saveFormSchema(
            @PathVariable("templateKey") String templateKey,
            @RequestBody @Valid SaveTemplateFormSchemaRequest body) {
        return formSchemaHandler.handle(
                new SaveTemplateFormSchemaCommand(currentTenant.currentTenant(), templateKey, body.formSchema()));
    }

    @DeleteMapping("/{templateKey}/draft/form-schema")
    public TemplateDTO clearFormSchema(@PathVariable("templateKey") String templateKey) {
        return formSchemaHandler.handle(
                new SaveTemplateFormSchemaCommand(currentTenant.currentTenant(), templateKey, null));
    }

    @PutMapping("/{templateKey}/sla")
    public SlaPolicyDTO saveSla(
            @PathVariable("templateKey") String templateKey,
            @RequestBody @Valid SaveSlaPolicyRequest body) {
        return slaHandlers.save(new SaveSlaPolicyCommand(currentTenant.currentTenant(), templateKey,
                body.dueAfterHours(), body.remindBeforeHours(), body.onOverdue(), body.escalateToUser()));
    }

    @GetMapping("/{templateKey}/sla")
    public SlaPolicyDTO sla(@PathVariable("templateKey") String templateKey) {
        return slaHandlers.get(currentTenant.currentTenant(), templateKey);
    }

    @DeleteMapping("/{templateKey}/sla")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSla(@PathVariable("templateKey") String templateKey) {
        slaHandlers.delete(currentTenant.currentTenant(), templateKey);
    }

    @PostMapping("/{templateKey}/publish")
    public TemplateDTO publish(@PathVariable("templateKey") String templateKey) {
        return publishHandler.handle(new PublishTemplateCommand(
                currentTenant.currentTenant(), templateKey, currentActor.currentActor()));
    }

    @PostMapping("/{templateKey}/disable")
    public TemplateDTO disable(@PathVariable("templateKey") String templateKey) {
        return enabledHandler.handle(new SetTemplateEnabledCommand(currentTenant.currentTenant(), templateKey, false));
    }

    @PostMapping("/{templateKey}/enable")
    public TemplateDTO enable(@PathVariable("templateKey") String templateKey) {
        return enabledHandler.handle(new SetTemplateEnabledCommand(currentTenant.currentTenant(), templateKey, true));
    }

    @GetMapping("/{templateKey}/versions")
    public List<TemplateVersionDTO> versions(@PathVariable("templateKey") String templateKey) {
        return versionsHandler.handle(new ListTemplateVersionsQuery(currentTenant.currentTenant(), templateKey));
    }

    @GetMapping("/{templateKey}/versions/{version}")
    public TemplateVersionDetailDTO version(
            @PathVariable("templateKey") String templateKey,
            @PathVariable("version") int version) {
        return versionHandler.handle(new GetTemplateVersionQuery(currentTenant.currentTenant(), templateKey, version));
    }
}
