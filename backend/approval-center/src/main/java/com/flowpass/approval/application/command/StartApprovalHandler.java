package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.application.common.FieldViolation;
import com.flowpass.approval.application.port.ApproverResolution;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.CandidateResolverPort;
import com.flowpass.approval.application.port.FormValidationPort;
import com.flowpass.approval.application.port.IdempotencyPort;
import com.flowpass.approval.application.port.OutboxPublisherPort;
import com.flowpass.approval.application.port.WorkflowEnginePort;
import com.flowpass.approval.application.port.WorkflowStep;
import com.flowpass.approval.contract.dto.ApprovalInstanceDTO;
import com.flowpass.approval.domain.audit.AuditAction;
import com.flowpass.approval.domain.audit.AuditRecord;
import com.flowpass.approval.domain.audit.AuditRecordRepository;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.Money;
import com.flowpass.approval.domain.definition.DefinitionRepository;
import com.flowpass.approval.domain.definition.PublishedDefinition;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalInstanceRepository;
import com.flowpass.approval.domain.task.ApprovalTask;
import com.flowpass.approval.domain.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.flowpass.approval.application.common.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Start-approval use case. Orchestrates the idempotency claim, definition resolution, aggregate
 * creation, persistence and the transactional outbox — in that order — inside one local transaction.
 *
 * <p>No business rules live here; the aggregate enforces its own invariants ({@link ApprovalInstance}).
 * This handler only wires the domain through its ports and translates to the public contract DTO.</p>
 */
@Service
public class StartApprovalHandler {

    private static final Logger log = LoggerFactory.getLogger(StartApprovalHandler.class);
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final IdempotencyPort idempotencyPort;
    private final DefinitionRepository definitionRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final OutboxPublisherPort outboxPublisher;
    private final WorkflowEnginePort workflowEnginePort;
    private final CandidateResolverPort candidateResolver;
    private final WorkflowTaskMaterializer taskMaterializer;
    private final FormValidationPort formValidation;
    private final AuditRecordRepository auditRecordRepository;

    public StartApprovalHandler(
            IdempotencyPort idempotencyPort,
            DefinitionRepository definitionRepository,
            ApprovalInstanceRepository instanceRepository,
            OutboxPublisherPort outboxPublisher,
            WorkflowEnginePort workflowEnginePort,
            CandidateResolverPort candidateResolver,
            WorkflowTaskMaterializer taskMaterializer,
            AuditRecordRepository auditRecordRepository,
            FormValidationPort formValidation) {
        this.idempotencyPort = idempotencyPort;
        this.definitionRepository = definitionRepository;
        this.instanceRepository = instanceRepository;
        this.outboxPublisher = outboxPublisher;
        this.workflowEnginePort = workflowEnginePort;
        this.candidateResolver = candidateResolver;
        this.taskMaterializer = taskMaterializer;
        this.formValidation = formValidation;
        this.auditRecordRepository = auditRecordRepository;
    }

    @PreAuthorize("hasAuthority('" + Permissions.REQUEST_CREATE + "')")
    @Transactional
    public ApprovalInstanceDTO handle(StartApprovalCommand cmd) {
        // 0. Who is the initiator? Derived from the authenticated actor, never trusted from the body.
        ApproverRef initiator = resolveInitiator(cmd);

        // 1. Idempotency claim (same tx: a failed write rolls the claim back, caller may retry).
        String idemKey = normalize(cmd.idempotencyKey());
        idempotencyPort.claim(cmd.tenantId(), cmd.applicationId(), idemKey, null);

        // 2. Resolve the published definition to pin its version as the instance snapshot.
        PublishedDefinition def = definitionRepository.findPublished(cmd.tenantId(), cmd.templateKey())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.TEMPLATE_NOT_FOUND,
                        "Template not published: " + cmd.templateKey()));

        // 2a. Validate the application's input before anything is created: the template's form schema (the one
        //     published with the pinned version) plus the platform's own rules for amount / currency.
        validateInput(cmd, def);

        // 3. Build the aggregate (state + invariants live in the domain; no rules here).
        ApprovalInstance instance = ApprovalInstance.start(
                cmd.tenantId(),
                cmd.applicationId(),
                cmd.externalBusinessKey(),
                def.templateKey(),
                initiator,
                resolveMoney(cmd.variables()),
                def.definitionVersion(),
                cmd.variables() == null ? Map.of() : cmd.variables());

        // 4. Persist the aggregate.
        instanceRepository.save(instance);

        // 5. Resolve first-round approvers (decision-context concern behind a port). Nobody resolved is
        //    not an approval: the engine parks the run for manual handling.
        ApproverResolution resolution = candidateResolver.resolve(cmd.tenantId(), def.templateKey(), initiator,
                instance.variables());
        List<CandidateApprover> candidates = resolution.approvers();
        if (resolution.failed()) {
            log.warn("Instance {} (tenant {}): approver resolution failed, routed to manual review: {}",
                    instance.id().value(), cmd.tenantId().value(), resolution.problems());
        }

        // 6. Drive the process engine (the source of truth for routing) and mirror the user tasks it
        //    opened as business tasks. The engine id stays inside the adapter.
        WorkflowStep step = workflowEnginePort.start(
                instance.tenantId(),
                instance.id().value(),
                def.templateKey(),
                def.definitionVersion(),
                instance.variables(),
                candidates);
        log.debug("Started workflow for instance {}: {} task(s) opened", instance.id().value(),
                step.newTasks().size());
        taskMaterializer.materialize(instance.tenantId(), instance.id().value(), step.newTasks());

        // 7. Emit domain events into the transactional outbox (M3 wires the async worker).
        outboxPublisher.append(instance.tenantId(), instance.id().value(), "ApprovalInstance",
                instance.drainEvents());

        // 8. Append an immutable audit entry for the creation (who initiated, when). Append-only.
        auditRecordRepository.append(AuditRecord.of(
                instance.tenantId(),
                instance.id().value(),
                null,
                instance.initiator(),
                AuditAction.STARTED,
                resolution.failed()
                        ? "Approver resolution failed, routed to manual review: "
                                + String.join("; ", resolution.problems())
                        : null,
                Instant.now()));

        return toDto(instance);
    }

    /**
     * A user always starts as themselves. Only a {@code SYSTEM} actor (a business system) may start on
     * behalf of another principal; any other mismatch is forbidden.
     */
    private ApproverRef resolveInitiator(StartApprovalCommand cmd) {
        ApproverRef requested = cmd.onBehalfOf();
        if (requested == null || requested.equals(cmd.actor())) {
            return cmd.actor();
        }
        if (SYSTEM_ACTOR.equals(cmd.actor().type())) {
            return requested;
        }
        log.warn("Denied start on behalf of {}:{} (tenant {}) for actor {}:{} — only SYSTEM may delegate initiator",
                requested.type(), requested.id(), cmd.tenantId().value(), cmd.actor().type(), cmd.actor().id());
        throw new BusinessException(BusinessErrorCode.FORBIDDEN,
                "Only a SYSTEM actor can start an approval on behalf of another principal");
    }

    /**
     * Refuse an application whose input is not acceptable, listing every offending field at once. The template's
     * form schema decides what the fields may be; the platform's own rules (amount is a non-negative number with at
     * most two decimals, and comes with an ISO-4217 currency) apply to every template, schema or not — a field the
     * schema already complained about is not reported twice.
     */
    private void validateInput(StartApprovalCommand cmd, PublishedDefinition def) {
        Map<String, Object> variables = cmd.variables() == null ? Map.of() : cmd.variables();
        List<FieldViolation> violations = new ArrayList<>();
        definitionRepository.findFormSchema(cmd.tenantId(), def.templateKey(), def.definitionVersion())
                .ifPresent(schema -> violations.addAll(formValidation.validate(schema, variables)));

        Set<String> reported = new HashSet<>();
        violations.forEach(v -> reported.add(v.field()));
        platformViolations(variables).stream()
                .filter(v -> !reported.contains(v.field()))
                .forEach(violations::add);

        if (!violations.isEmpty()) {
            String summary = violations.stream()
                    .map(v -> v.field().isEmpty() ? v.message() : v.field() + ": " + v.message())
                    .limit(5)
                    .collect(Collectors.joining("; "));
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED,
                    "The application is not valid (" + violations.size() + " problem"
                            + (violations.size() == 1 ? "" : "s") + "): " + summary, violations);
        }
    }

    private List<FieldViolation> platformViolations(Map<String, Object> variables) {
        List<FieldViolation> violations = new ArrayList<>();
        Object amount = variables.get("amount");
        Object currency = variables.get("currency");
        if (amount != null) {
            BigDecimal value = numeric(amount);
            if (value == null) {
                violations.add(new FieldViolation("amount", "must be a number"));
            } else {
                if (value.signum() < 0) {
                    violations.add(new FieldViolation("amount", "must not be negative"));
                }
                if (value.stripTrailingZeros().scale() > 2) {
                    violations.add(new FieldViolation("amount", "must have at most 2 decimal places"));
                }
            }
            if (currency == null || currency.toString().isBlank()) {
                violations.add(new FieldViolation("currency", "is required when an amount is given"));
            }
        }
        if (currency != null && !currency.toString().isBlank() && !CURRENCY.matcher(currency.toString()).matches()) {
            violations.add(new FieldViolation("currency", "must be a 3-letter ISO-4217 code such as MYR"));
        }
        return violations;
    }

    /** The value as a number, or null when it is not one (numbers and numeric strings are accepted). */
    private BigDecimal numeric(Object value) {
        if (value instanceof Boolean) {
            return null;
        }
        try {
            return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Money resolveMoney(Map<String, Object> variables) {
        if (variables == null) {
            return null;
        }
        Object amount = variables.get("amount");
        Object currency = variables.get("currency");
        if (amount == null || currency == null) {
            return null;
        }
        return new Money(toBigDecimal(amount), currency.toString());
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private String normalize(String key) {
        return (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
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
                Instant.now().toString());
    }
}
