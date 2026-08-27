# DDD Coding Rules (Hard Constraints)

> These are **always-on** hard constraints for every agent. They are a companion to `.agents/AGENTS.md`.
> Violating any of these is a defect, not a suggestion.

## 1. Package / Module Structure

Mirror bounded context → module → aggregate. A class lives where its responsibility lives.

```text
<service>
└─ interfaces       # controllers, consumers, schedulers, DTO
├─ application      # use cases, command/query handlers
├─ domain
│  ├─ <context>     # aggregates, value objects, domain services, repository interfaces
│  └─ event         # domain events
└─ infrastructure   # adapters, persistence, outbox, external clients
```

**Rules:**

- Repository interfaces live in `domain`; their implementations live in `infrastructure`.
- Ports (outbound interfaces) live in `application` or `domain`; adapters live in `infrastructure`.
- DTOs and contract objects are separate from domain entities. Never expose an entity as an API response.

## 2. Dependency Direction (Enforced)

```text
interfaces   →  application  →  domain
infrastructure  →  implements ports (application/domain)
```

- A class may depend on its own layer, or inward (deeper). Dependence outward = violation.
- `domain` depends on NOTHING outside itself (no Spring, JPA, HTTP, Kafka, Keycloak, Flowable, Lombok not used in aggregates).
- `application` orchestrates via ports; it knows about `domain` but not about adapters.
- `infrastructure` may depend on `application` and `domain` to implement ports, but never the reverse.

## 3. Forbidden Imports in Domain

The `domain` package MUST NOT contain:

- `org.springframework.*`, `javax.*`/`jakarta.*` persistence annotations
- Keycloak, JWT, Spring Security classes
- HTTP / servlet classes
- Kafka / RocketMQ / MQ client classes
- Flowable engine classes
- Any ORM entity mapping (JPA `@Entity`, MyBatis mapper)

If a domain class needs one of these, the responsibility is misplaced. Move it to an adapter and define a port.

## 4. Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Domain event | past tense | `ApprovalCompleted` |
| Command | verb | `ApproveRequestCommand` |
| Query | noun | `GetApprovalQuery` |
| Aggregate | business noun | `Approval`, `ApprovalTask` |
| Value object | precise noun | `Money`, `TenantId`, `ApprovalId` |
| Repository port | `...Repository` | `ApprovalRepository` |
| Port (outbound) | `...Port` | `CurrentActorPort`, `AuthorizationPort` |
| State enum | business state, not display text | `PENDING_APPROVAL`, `RUNNING` |

- **Never** reuse display copy as a state enum value.
- **Never** name an aggregate after an infrastructure concept (e.g. `JpaApproval`).

## 5. Aggregates & Invariants

- Aggregates enforce **invariants** and are the only owner of state changes. No setters that bypass invariants.
- Use **optimistic versioning** where concurrent mutations are possible (task completion, instance approval).
- A write typically: validate → mutate → register domain event → repository save → outbox in the **same local transaction**.
- Do not perform external side effects inside a transaction. Do them after commit, retryable.

## 6. Application Layer

- A use case = one application handler. Keep it focused; orchestrate repositories/ports; do not place business rules here.
- Security annotations live at the **application handler** (the authorization boundary), not only on the controller.
- Map between contract DTOs and domain objects here; never leak domain entities out.

## 7. Multi-Tenancy

- All business tables carry a trusted `tenant_id`; composite unique keys include `tenant_id`.
- Repositories expose tenant-scoped methods only (`findByTenantIdAndId`, `findForUpdate(tenantId, id)`).
- Tenant must be propagated to cache keys, Kafka messages, S3 paths, logs, and metrics.
- Never derive tenant from a client-submitted value alone; resolve and verify server-side.

## 8. Idempotency & Concurrency

- Public write APIs accept an `Idempotency-Key`. Store a tenant-scoped idempotency record.
- Task completion uses a status precondition (`PENDING`/`CLAIMED` only) + optimistic lock.
- Domain events are versioned; consumers are idempotent by `eventId`; older versions never overwrite newer.

## 9. Observability & Safety

- Use structured logs; never log tokens, secrets, passwords, or sensitive form values.
- Store time in **UTC**; render per-tenant timezone. Money as `decimal` + `currency`.
- Errors outside the domain map to **stable business error codes**, never raw exceptions, SQL, or stack traces.
- External systems adapters need timeouts, retry policy, and a defined failure strategy.

## 10. Architecture Guardrails

- Keep **ArchUnit rules** for the dependency direction and domain purity. Fail the build on violations.
- Never bypass these rules "just this once". If a rule truly cannot hold, raise it in review and update the charter deliberately.
