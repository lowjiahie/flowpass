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

# 2. Run the service (Flyway migrates the schema on startup)
cd approval-center
mvn spring-boot:run
# or from the backend root:
mvn -pl approval-center spring-boot:run
```

Endpoints once running (`http://localhost:8080`):

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | liveness/readiness |
| `GET /v3/api-docs` | OpenAPI JSON |
| `GET /swagger-ui.html` | Swagger UI |
| `GET /v1/approval-instances/{id}` | read path example (tenant from `X-Tenant-Id`, default `default`) |

## Verify

```bash
cd backend
mvn test   # ArchUnit dependency rules + aggregate invariant unit tests (no DB required)
```

## Conventions

All work must follow `.agents/AGENTS.md`, the DDD rules and the 6-step dev workflow. Domain code
must never import Spring/JPA/MyBatis/Flowable/Keycloak; state changes go through aggregates; all
writes carry a trusted `tenant_id`. See `.agents/rules/ddd-coding-rules.md`.
