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

### Local demo (Keycloak login + demo data)

The `dev` profile is for tests and curl (identity from `X-Actor-*` headers; the web UI cannot use it in SSO mode).
For the full UI with real login:

```bash
cd backend
docker compose up -d                       # PostgreSQL + Keycloak (imports realm saas-platform)
mvn -pl approval-center spring-boot:run -Dspring-boot.run.profiles=keycloak
python dev/seed_demo.py                    # demo template, rules, SLA and org directory in tenant `default`
cd ../frontend && npm install && npm run dev      # http://localhost:5173  (VITE_USE_MOCK=false)
```

Demo users (password = username): `demo` (requester + all admin rights), `alice` (demo's manager), `bob` (alice's manager),
`carol` (finance role). Rules: amount >= 10000 -> manager, manager's manager, finance; >= 1000 -> manager, finance; else manager.
The API authorities come from the token's `resource_access.approval-api.roles`; the realm file defines them and the
`approval-seed` client (password grant, local realm only) is what the seed script uses. Changing the realm file needs
`docker compose up -d --force-recreate keycloak` (it is imported on first boot only).

### Flow designer

The template page draws the flow with bpmn-js (`frontend/src/components/BpmnEditor.tsx`): approval rounds and decisions are
dragged from a palette that only offers what the server accepts, paths get their condition in a side panel, and a standard
1-5 round flow can be generated as a starting point (`frontend/src/utils/approvalFlow.ts`).

Because the run's outcome is the end event it reaches, drawing is only safe if the server also checks the *structure*.
`FlowDefinitionValidator` therefore refuses, on save and on publish, a flow where:

- an approval round (multi-instance user task) is not followed by an exclusive gateway with a `${rejected}` flow to
  `endRejected` - a rejection would otherwise finish as *approved*;
- a round can be entered without a `${hasApprovers}` / `${hasApproversN}` guard (matching its collection) - an empty round
  would pass by itself; the only other way in is a `${returned}` flow;
- a gateway with several ways out has no default flow, or a non-default flow has no condition - the engine would fail at
  run time with nowhere to go.

On top of the element/attribute/expression whitelist this means a tenant-drawn flow cannot change the meaning of "approved".
The XML tab stays for anything the palette cannot express; it goes through the same checks.

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

### Task actions: transfer, add-sign, return

Besides approve / reject, the approver of an open task (or a member of the assigned role) can — all need the
`approval.request.approve` authority and are recorded on the audit ledger:

| Endpoint | What it does |
|---|---|
| `POST /v1/tasks/{id}/transfer` `{"to": "u_x", "comment"}` | **Transfer**: hand the task to one other user. It keeps its slot in the round; a role task becomes a task of that one user. |
| `POST /v1/tasks/{id}/add-approver` `{"userId": "u_x", "comment"}` | **Add-sign** (parallel): ask another user to approve this round as well. The round now also needs their approval, and they may reject. Returns the new task. |
| `POST /v1/tasks/{id}/return` `{"comment"}` | **Return**: send the request back to the **previous round**. The approvals already given in the current round are discarded, its other open tasks are cancelled, and the previous round opens again for the same approvers. The instance stays `RUNNING`. |

- Who can be brought in (transfer target, added approver): not the initiator, and not anyone who already holds, or held, a
  task of this request. A finished request, a blank or unknown-shape user, or a finished task is refused.
- **Return** needs an earlier round: a request still in round 1 is refused (`approval.illegal_state`). The engine routes the
  return through the definition (the built-in flow does for rounds 2 and 3); a definition that does not route it is refused
  and **nothing changes** — the attempt is rolled back as a whole. Returning to the *initiator* for editing and resubmitting
  is not supported yet.
- After a return, tasks are not reused: the reopened round gets new tasks (the earlier, approved ones stay in the history),
  and an approver added by add-sign is not carried into the re-run (it uses the rule's original approvers).
- Safety: the same authorization as approve / reject (assignee or role holder; the initiator cannot act through a role),
  one winner under concurrency, and everything happens in one transaction with the engine.

### Deadlines, reminders, escalation & delegation

**SLA policy** (per template, `approval.template.manage`): `PUT/GET/DELETE /v1/admin/templates/{key}/sla`
with `{"dueAfterHours", "remindBeforeHours"?, "onOverdue": "NOTIFY_ONLY|ESCALATE_TO_MANAGER|ESCALATE_TO_USER", "escalateToUser"?}`.
Each task snapshots `dueAt` / `remindAt` **when it opens** (so a later round counts from when it actually starts); changing the
policy affects only tasks opened afterwards. `ESCALATE_TO_USER` requires `escalateToUser`; the reminder must come before the deadline.

**Deadline worker** (`ProcessSlaHandler`, run by `SlaScheduler`, off unless `flowpass.sla.scheduler-enabled=true`):
- a reminder emits `approval.task.reminded` once; an overdue task emits `approval.task.overdue` once and, per the policy,
  is handed to the assignee's manager (from the org directory) or the configured user: `approval.task.escalated`, audit
  `ESCALATED` / `OVERDUE`, and the engine task is re-assigned in the same transaction.
- Each step is **claimed by a conditional update**, so several nodes can run the worker without duplicates; a failure on one task
  does not stop the rest and is retried next run.
- Escalation never goes to the initiator or someone already involved in the request; if nobody is eligible the task is
  only notified (the reason is in the audit comment). Finished requests are left alone. A role task can only be escalated to the fallback user.

**Delegation** (`/v1/delegations`, the caller delegates their own tasks): `POST {"delegateId", "startsAt"?, "endsAt"}` (ends in
the future, at most 180 days, no overlapping period, not to yourself), `GET` mine, `DELETE /{id}` (only the owner). Evaluated
when a task opens: a user task goes to the delegate (one level only, audited as `DELEGATED`); skipped if the delegate is the initiator
or already in the request. Role tasks are not delegated, and tasks already open are not moved.

### Rule conditions

A decision rule matches when **all** of its parts hold: the amount band, the exact field matches, and an optional
`expression` written in a small, safe condition language:

```text
amount >= 10000 || (urgent && region in ['EU', 'UK'])
department in ['IT', 'HR'] && amount > 1000
!(region in ['US']) && reason contains 'laptop'
total <= budget * 1.1
customer.tier == 'gold'
```

- Operators: `== != < <= > >=`, `in [..]`, `not in [..]`, `contains`, `&& || !` (or `and or not`), `+ - * /`, parentheses;
  literals are numbers, `'text'`, `true`, `false`, `null` and `[lists]`; `a.b.c` reads nested fields.
- **Strict types:** the text `"250"` is not the number `250` (so `amount` must be sent as a number). A missing field is
  `null`, and `<` / `>` against `null` or a mismatched type is just `false` — a rule that cannot apply does not match.
  Guard optional fields explicitly: `endDate == null || endDate > startDate`. ISO dates (`2026-03-01`) compare correctly as text.
- **Safe by construction:** a closed interpreter — no function or method calls, no property calls, no assignment, no I/O;
  length, nesting depth and list size are capped. An invalid or unsafe condition is rejected when the rule is defined.
- The same language writes **cross-field business checks** in a form schema: `"x-rules": [{"expression":
  "endDate > startDate", "message": "End date must be after the start date", "field": "endDate"}]`. They are versioned
  with the schema, run after the types are valid, and every failing message is returned in the `errors` list.

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
| `INITIATOR_MANAGER` | `initiator` or `1`..`5` | a task for the initiator's manager; a number N is the manager N levels up (`2` = the manager's manager) |
| `DEPARTMENT_HEAD` | `initiator` | a task for the head of the initiator's department |
| `FORM_FIELD` | variable name | a task for the user named in that form variable |

- **Approval rounds (chains):** each approver of a rule may carry a `round` (1 to 5, default 1). The approvers of a round
  act in parallel and **all** must approve (a `ROLE` counts as one approver: any member); the next round opens only when the
  previous one is done, and a round with nobody is skipped. A rejection in any round ends the request at once. Combine
  rounds with rule conditions to make the chain depend on the request, e.g. `amount >= 10000` → manager, then finance, then
  the manager's manager; `amount >= 1000` → manager, then finance; otherwise just the manager.
  All rounds are resolved when the request starts (a later round does not follow org changes made afterwards), and
  nobody is asked twice: a person already in an earlier round, or the initiator, is dropped, and a round left empty is
  removed. The built-in `purchase_request` flow supports 3 rounds; a tenant definition may use `approvers2`..`approvers5`
  collections (one per round) and `hasApprovers2`..`hasApprovers5` in its conditions.
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
