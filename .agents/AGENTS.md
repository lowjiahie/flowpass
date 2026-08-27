# Master Development Charter

> This is the **single source of truth** for all agents working in this repository.
> Every cross-agent MUST read this file before starting any task.

## 1. Project Direction (Context)

This repository is the design/solution basis for a SaaS platform composed of:

- A **generic approval workflow** service (configurable templates, forms, rules, Webhook, multi-tenant).
- A **SaaS identity / SSO / multi-tenant authorization** foundation (planned to be backed by Keycloak).

Until identity services are available, the approval service may stand up with **temporary adapters** behind the domain ports. The domain must never depend on the identity implementation.

## 2. The Core Philosophy: DDD

We build with **Domain-Driven Design**. Three non-negotiable ideas:

1. **Bounded Contexts express business capabilities, not technical layers.**
   `definition`, `runtime`, `task`, `decision`, `integration`, `audit` are separate contexts — never merge them because they share a framework.

2. **The Domain is the heart.** Business rules live in aggregates and domain services. They are pure Java with no framework (no Spring, JPA, HTTP, Keycloak, Kafka, Flowable).

3. **Dependencies point inward.** Outer layers depend on inner layers, never the reverse.

## 3. Layered Dependency Direction (Hard)

```
interfaces   →  application  →  domain
infrastructure  →  implements  application/domain ports
```

- `interfaces`: REST/Dubbo controllers, message consumers, scheduler entry points. No business rules.
- `application`: use cases, command/query handlers, transaction boundaries, DTO mapping. Orchestrates.
- `domain`: aggregates, value objects, domain services, domain events, repository/port *interfaces*, invariants. Pure.
- `infrastructure`: JPA/MyBatis adapters, Flowable adapter, Keycloak/jwt adapter, Kafka/Outbox, webhook. Implements ports.

**Rule:** A class may only reference classes in its own layer or one layer deeper (inward). Outward references are forbidden.

## 4. Mandatory Workflow (Every Task)

Every task MUST follow the 6-step workflow in `.agents/skills/ddd-dev-workflow/SKILL.md`:

1. **Understand** — read the charter + relevant bounded context before coding.
2. **Locate ownership** — decide which bounded context / aggregate owns this change.
3. **Model the domain** — define the change in domain terms (aggregate, use case, invariant) without touching infrastructure.
4. **Implement** — fill the ports with adapters, repository, outbox inside the correct layer.
5. **Verify** — check invariants, dependency direction, tests.
6. **Deliver** — acceptance criteria, commit discipline, documentation.

No agent may skip to implementation before completing steps 1–3.

## 5. Hard Red Lines (Never Violate)

- **Domain must not import** Spring, Spring Security, Keycloak, JWT, JPA/ORM, HTTP, Kafka, or Flowable.
- **Never expose framework internals in public models.** Flowable IDs are mapped out; public API uses own IDs.
- **Never mock or cheat permission checks only in the front-end.** All write paths re-authorize in the application handler.
- **Never update aggregate state directly from SQL or an admin script.** State changes go through domain commands.
- **Never bypass `tenant_id`** in queries, unique keys, cache keys, Kafka messages, S3 paths, or logs.
- **Never write outside the owning layer.** Do not put business logic in a controller or infrastructure adapter.

## 6. Multi-Tenancy & Identity (Context Dependent)

- All business tables carry a trusted `tenant_id`; repositories scope by tenant + id.
- Identity is accessed only through ports (`CurrentActorPort`, `AuthorizationPort`, `EventPublisherPort`). Until Keycloak exists, use a temporary adapter and leave a clear `TODO`.

## 7. Quality Gates (Definition of Done)

A change is "done" only when ALL hold:

- [ ] Layer dependency direction is correct (`ArchUnit` would pass).
- [ ] Domain code is framework-agnostic and tested for invariants.
- [ ] A security annotation or explicit authorization check exists on every public write handler.
- [ ] Tenant scoping is present in repository + data layer.
- [ ] Write path is idempotent where the API requires an `Idempotency-Key`.
- [ ] Tests cover happy path, boundary/edge cases, and at least the documented concurrency scenario for the touched aggregate.
- [ ] No secrets, tokens, or sensitive fields in logs.

## 8. Coding Standards (Synopsis)

See `.agents/rules/ddd-coding-rules.md` for the full set. Highlights:

- **Package structure** mirrors bounded context → module → aggregate.
- **Naming**: domain events past tense (`ApprovalCompleted`), commands verbs (`ApproveRequestCommand`), state enums distinct from display text.
- **Time**: store in UTC; display per tenant timezone. **Money**: `decimal` + `currency`.
- **API**: stable `/v1` resource naming; cursor pagination; stable business error codes, never raw exceptions/SQL.

## 9. How a Cross-Agent Should Use This Charter

If you are one of several agents working in parallel:

1. Read this file first.
2. Confirm which bounded context and aggregate is yours before coding.
3. Do not silently expand scope into another context; note it and coordinate.
4. Keep interfaces (ports/contracts) stable — changing a port affects other agents.
