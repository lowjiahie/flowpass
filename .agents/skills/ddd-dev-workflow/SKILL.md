---
name: ddd-dev-workflow
description: The 6-step DDD-aware development workflow for every task in this repository. Use when implementing, modifying, or reviewing any code change so the agent follows a consistent, domain-driven process and respects layered dependencies.
---

# DDD Development Workflow

Every task in this repository MUST go through these six steps. Do not jump to code before
steps 1–3. The workflow guarantees consistent, DDD-compliant, cross-agent-compatible output.

## When to use

- Implementing a new feature / API.
- Modifying an existing bounded context or aggregate.
- Refactoring across layers.
- Any change that touches a repository, port, adapter, or aggregate.

## The 6 Steps

### Step 1 — Understand
- Read `.agents/AGENTS.md` (charter) and `.agents/rules/ddd-coding-rules.md`.
- Read the relevant bounded context documents before any business code.
- List the goal, the accepted inputs/outputs, and the invariants that must hold.

### Step 2 — Locate Ownership
- Decide which bounded context owns this change (`definition`, `runtime`, `task`, `decision`, `integration`, `audit`).
- Identify the aggregate(s) and the layer each touched file belongs to.
- Do not expand into another context; note and coordinate instead.

### Step 3 — Model the Domain
- Express the change in domain terms: aggregate, value object, domain service, invariant, domain event.
- Define the port/contract necessary for the change (what the domain needs from outside).
- Do NOT yet write infrastructure.

> **Checkpoint:** only proceed after you can state the aggregate, its invariant, and the new port/event.

### Step 4 — Implement
- Write the domain change first (pure, no framework).
- Then implement the port adapter in `infrastructure`.
- Wire the application handler (use case + transaction boundary) and the entry point (`interfaces`).
- Keep dependencies inward: `interfaces → application → domain`, `infrastructure implements ports`.

### Step 5 — Verify
- Confirm invariants hold under the documented edge cases and the aggregate's concurrency scenario.
- Confirm dependency direction (ArchUnit mental check): no forbidden imports in `domain`.
- Confirm tenant scoping and authorization placement.
- Confirm idempotency where the API requires it.
- Run the relevant unit/integration tests; fix failures.

### Step 6 — Deliver
- Map output to the acceptance criteria (Definition of Done in the charter).
- Keep public ports/contracts stable; document any intentional contract change.
- Follow commit discipline; leave no secrets/TODOs that bypass rules.

## DDD Checklist (before finish)

- [ ] Change belongs to the correct bounded context and aggregate.
- [ ] Domain code is framework-agnostic (no Spring/JPA/HTTP/Kafka/Keycloak/Flowable).
- [ ] State changes go through domain commands; no direct SQL/admin update of aggregate state.
- [ ] Repository interface in `domain`; implementation in `infrastructure`.
- [ ] Authorization / tenant check present on the public write path.
- [ ] `tenant_id` scoping in repository + data layer.
- [ ] Write path idempotent where an `Idempotency-Key` is expected.
- [ ] Tests: happy path + boundary/edge + the aggregate's concurrency case.
- [ ] No secrets, tokens, or sensitive values logged.

## Examples

**Good — adding a task transfer use case:**
1. `task` context owns it; aggregate `ApprovalTask`; no other context touched.
2. Domain: new `transfer(UserId target)` method enforcing assignee/invariants; event `ApprovalTaskTransferred`.
3. Port: `CurrentActorPort` (already exists) reused; no new infra dependency.
4. Application: `TransferTaskHandler` with `@RequireTenantAccess` + `@RequireResourcePermission`.
5. Tests for happy path, wrong-actor reject, concurrent completion.

**Bad — would violate the charter (do not do this):**
- Adding a JPA `@Entity` annotation inside `domain`.
- Putting business routing logic inside a `infrastructure` Flowable adapter.
- Adding a security check only in the controller and trusting it.
- Writing `UPDATE approval_task SET ... WHERE id = ...` directly from a script.

## Notes

- One task = one focused change across the correct layers. Do not leak cross-context changes into a single step.
- If a required port does not exist, define it in `domain`/`application` first, then implement the adapter in Step 4.
