# Flowpass Approval Backend

Generic, multi-tenant approval workflow service — Phase-1 M0 skeleton. Built with Domain-Driven
Design and a strict inward dependency direction enforced by ArchUnit.

## Stack

| Layer | Choice |
|---|---|
| Runtime | JDK 21 |
| Framework | Spring Boot 3.3 |
| Build | Maven (multi-module) |
| RPC | Dubbo 3 Triple (contract module; REST still used for external systems) |
| Persistence | MyBatis (explicit SQL) + PostgreSQL + Flyway |
| Process engine | Flowable behind a `WorkflowEnginePort` (not yet wired in M0) |
| Guardrails | ArchUnit (dependency direction + domain purity) |

## Modules

```
backend/
├─ approval-contract/   # stable cross-boundary DTOs + service interfaces (framework-free)
├─ approval-center/     # the core service: interfaces / application / domain / infrastructure
└─ docker-compose.yml   # local PostgreSQL
```

## Layered structure (DDD)

```
com.flowpass.approval
├─ interfaces    # REST/Dubbo controllers, error mapping. No business rules.
├─ application   # use cases, command/query handlers, DTO translation.
├─ domain        # bounded contexts (definition, runtime, task, decision, integration, audit)
│                #   + common value objects + domain events. Framework-agnostic.
└─ infrastructure# MyBatis adapters (implements ports), config, later flowable/outbox/webhook.
```

## Run (local)

```bash
# 1. Start PostgreSQL
cd backend
docker compose up -d

# 2. Run the service locally with the explicit `dev` identity profile (Flyway migrates on startup)
cd approval-center
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# or from the backend root:
mvn -pl approval-center spring-boot:run -Dspring-boot.run.profiles=dev
```

### Identity & authorization

The service is **secure by default**: without the `dev` profile it is a JWT resource server
(`spring.security.oauth2.resourceserver.jwt.issuer-uri` must be set) and every business endpoint needs a
valid Keycloak access token. The tenant comes from the token's `tenant_id` claim, the actor from `sub`, and
permissions from the `approval-api` client roles (`resource_access.approval-api.roles`).

The `dev` profile (local only, refuses to start with `flowpass.environment=production`) replaces the IdP
with request headers:

| Header | Meaning |
|---|---|
| `X-Actor-Id` | acting user id (required, otherwise 401) |
| `X-Actor-Type` | `USER` (default) or `SYSTEM` |
| `X-Actor-Authorities` | optional comma-separated authorities (default: all) — pass a subset to try 403s |
| `X-Tenant-Id` | tenant (default `default`) |

Authorization is enforced in the application handlers (`@PreAuthorize` on the authority, domain policy on
the resource): only a task's assignee can approve/reject it, only an instance's initiator can withdraw it
(`POST /v1/approval-instances/{id}/withdraw`). The acting user is never read from a request body.
Authorities: `approval.request.{create,read,approve,reject,audit}` and
`approval.{template,rule,webhook}.manage`.

Endpoints once running (`http://localhost:8080`):

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | liveness/readiness |
| `GET /v3/api-docs` | OpenAPI JSON |
| `GET /swagger-ui.html` | Swagger UI |
| `GET /v1/approval-instances/{id}` | read path example (needs an identity — see above) |

### Workflow & tasks

The process engine (Flowable, behind `WorkflowEnginePort`) is the source of truth for **routing**; the
approval task is the business view of one engine user task.

- On start, approvers are resolved from the decision matrix and handed to the engine, which opens one user
  task per approver (BPMN multi-instance step, see `processes/purchase_request.bpmn20.xml`). Every use case
  that advances a run gets a `WorkflowStep` back — the tasks the engine opened and, if the run ended, the
  outcome (which end event it reached) — and mirrors them through `WorkflowTaskMaterializer`.
  Nothing is inferred from "no pending tasks left".
- `approval_task.workflow_task_ref` / `node_key` (V6) link a task to its engine step; the reference is
  opaque and never exposed by the API.
- No resolved approver never means approved: the run parks on an unassigned manual-review task.
- A rejection ends the step at once (the engine removes the sibling tasks, the platform marks them
  `CANCELLED`); a withdrawal deletes the run and cancels every open task.
- Engine deployments and runs are tenant-scoped; instance ids are generated (the business key is only
  unique per tenant + application, a second start for it is a `409`).
- Steps of one run are serialised by a row lock on `approval_workflow_ref` held in the caller's transaction,
  and Flowable's parallel-multi-instance *async leave* is switched off (`FlowableEngineConfig`): with it on,
  completion races the async executor and a step cannot be cancelled once part of it has finished.

### Templates & versions

A template owns an editable **draft** (BPMN XML) and a counter of **immutable published versions**.

| Endpoint (`/v1/admin/templates`, needs `approval.template.manage`) | Purpose |
|---|---|
| `POST /` | register a template (`DRAFT`) |
| `PUT /{key}/draft` | replace the draft — validated, nothing is stored if it breaks the flow contract |
| `POST /{key}/publish` | snapshot the draft as the next version (1, 2, ...); the publisher is recorded |
| `POST /{key}/disable`, `/enable` | stop / resume new instances; versions and running instances are untouched |
| `GET /`, `/{key}`, `/{key}/versions`, `/{key}/versions/{n}` | list, detail with draft, version history, one version's definition |

- A new instance runs the **latest** version and pins it; publishing a newer version never changes a running
  instance. The engine deploys exactly the stored `process_definition` XML (per tenant), not a classpath file.
- A template without a draft publishes the platform's built-in definition for its key, if one exists
  (`processes/{key}.bpmn20.xml`).
- **Tenant-authored BPMN is code**, so `FlowDefinitionValidator` (domain) accepts only the platform flow
  contract: start/end events, user tasks, exclusive/parallel gateways and sequence flows — no script, service,
  call or sub-process elements, no listeners or extension elements, no DOCTYPE/entities; conditions may only
  read variables (no property access, no method calls); the only engine attributes are
  `assignee` (`${approver}` or a literal `TYPE:id`), `collection="approvers"` and `elementVariable="approver"`;
  the process id equals the template key; one start event, everything reachable, end events are
  `endApproved` / `endRejected` (the end reached is the outcome).

### Input validation (forms)

A template can carry a **JSON Schema** describing the application's input (`variables`). It is versioned with the
template: publishing snapshots the draft schema as that version's immutable schema, and an application is validated
against the schema of the version it pins — a later, stricter schema never changes what a running instance started under.

| Endpoint | Purpose |
|---|---|
| `PUT /v1/admin/templates/{key}/draft/form-schema` `{"formSchema": {...}}` | set the draft schema (needs `approval.template.manage`) |
| `DELETE /v1/admin/templates/{key}/draft/form-schema` | remove it — the next published version is unconstrained |
| `GET /v1/templates/{key}/form` | the latest published version's schema, so a client can render / pre-validate the form (needs `approval.request.create`) |

- A refused application returns **400** `approval.validation_failed` with an `errors` list — every offending field at
  once (`{"field":"items[0].qty","message":"..."}`); nothing is created and the `Idempotency-Key` is not burned, so the
  caller can fix the data and retry with the same key.
- Types are strict (`"250"` is not a number) and `format` (`email`, `date`, `uuid`, ...), ranges, lengths, enums,
  required fields, `additionalProperties` and nested objects / arrays are enforced.
- **Platform rules for every template, schema or not:** `amount` is a non-negative number with at most 2 decimals and
  needs a `currency` (3-letter ISO-4217 code).
- **Schemas are tenant-authored, so they are held to a safe subset:** self-contained (`$ref` only to `#/...`; `$id`,
  `$anchor`, `$dynamicRef` refused), **no `pattern` / `patternProperties`** (regular expressions can be made to
  backtrack catastrophically — use `format`, `enum`, length or range keywords), bounded size and depth, valid against
  its meta-schema, an object at the root. Draft 2020-12 (default) or draft-07 when declared.

### Approvers

The approval matrix (`/v1/admin/decision-rules`) selects the first matching rule; each approver entry of the
rule has a `type`:

| type | `id` | becomes |
|---|---|---|
| `USER` | user id | a task for that user |
| `ROLE` | role name | **one** task for the whole role — any current member may act, the first wins; membership is checked when somebody acts |
| `INITIATOR_MANAGER` | `initiator` | a task for the initiator's direct manager |
| `DEPARTMENT_HEAD` | `initiator` | a task for the head of the initiator's department |
| `FORM_FIELD` | variable name | a task for the user named in that form variable |

- **All or nothing:** if any entry cannot be resolved (no manager on record, empty form field), the request
  goes to *manual review* with the reason recorded on its `STARTED` audit entry — it never runs with fewer
  approvers than the rule intended. `POST /v1/admin/decision-rules/preview` reports the same problems up front.
- **No self-approval:** the initiator is dropped from their own approvers, and cannot approve or reject their
  own request through a role they hold.
- `GET /v1/tasks?mine=true` lists tasks assigned to me **or to a role I hold**.
- The organisation (managers, department heads, role members) comes from the `OrgDirectory` port. TODO(keycloak):
  until the identity platform provides it, a temporary adapter reads `directory_user`, `directory_department`
  and `directory_role_member` (V8); seed them with SQL, there is deliberately no write API.

## Verify

```bash
cd backend
mvn test   # ArchUnit dependency rules + aggregate invariant unit tests (no DB required)
```

## Conventions

All work must follow `.agents/AGENTS.md`, the DDD rules and the 6-step dev workflow. Domain code
must never import Spring/JPA/MyBatis/Flowable/Keycloak; state changes go through aggregates; all
writes carry a trusted `tenant_id`. See `.agents/rules/ddd-coding-rules.md`.
