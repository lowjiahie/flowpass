package com.flowpass.approval.interfaces.rest;

import com.flowpass.approval.application.port.CurrentTenantPort;
import com.flowpass.approval.application.query.GetPublishedFormHandler;
import com.flowpass.approval.application.query.GetPublishedFormQuery;
import com.flowpass.approval.contract.dto.PublishedFormDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Applicant-facing read of a template's form: the JSON Schema a new application will be validated against, so a
 * client can render and pre-validate with the server's own rules. No business rules here.
 */
@RestController
@RequestMapping("/v1/templates")
public class TemplateFormController {

    private final GetPublishedFormHandler formHandler;
    private final CurrentTenantPort currentTenant;

    public TemplateFormController(GetPublishedFormHandler formHandler, CurrentTenantPort currentTenant) {
        this.formHandler = formHandler;
        this.currentTenant = currentTenant;
    }

    @GetMapping("/{templateKey}/form")
    public PublishedFormDTO form(@PathVariable("templateKey") String templateKey) {
        return formHandler.handle(new GetPublishedFormQuery(currentTenant.currentTenant(), templateKey));
    }
}
