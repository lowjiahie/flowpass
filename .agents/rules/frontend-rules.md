# Frontend Coding Rules (Hard Constraints)

> Always-on constraints for the `frontend/` app (React + TypeScript + Vite + Ant Design).
> These complement the backend DDD rules and are read by any agent working on the frontend.

## 1. Structure

```text
src/
├─ api/        # central HTTP + endpoint modules (never call axios directly in pages)
├─ layouts/    # shell (menu, header, route outlet)
├─ pages/      # route-level components, grouped by feature (pages/approval/...)
├─ router/     # route table + guards
├─ store/      # zustand stores (auth, later tenant)
├─ types/      # shared domain types (mirror backend contract)
└─ utils/      # token / pure helpers
```

- One feature = one folder under `pages/`; pages compose UI, they do NOT implement business rules.
- API and domain types live in `api/` and `types/`; they mirror the backend `/v1` contract.

## 2. TypeScript

- `strict` is on. **No `any`** (use `unknown` + narrowing). No `@ts-ignore` without a comment.
- Prefer `interface` for object shapes, `type` for unions/aliases.
- Accessibility/keys: never use array index as React key when a stable id exists.

## 3. API Layer (single source)

- All HTTP goes through `api/http.ts` (axios instance). Pages must not import axios directly.
- Endpoint modules (`api/approval.ts`, `api/auth.ts`) own the URL + typed return.
- Authorization token is attached by the axios request interceptor — never manual.
- When calling the real backend, flip the module-level `USE_MOCK` flag, not per-line.

## 4. State

- Use zustand only for cross-page shared state (auth, tenant, feature flags).
- Keep component-local state in `useState`/`useReducer`. Persist auth token to `localStorage` via `utils/auth.ts`.

## 5. UI & Ant Design

- Use antd components consistently. Use `App.useApp()` for `message`/`modal` (context-aware), not the static import.
- Reuse blocks via components; avoid copy-pasted style objects. Keep inline styles minimal.
- Tables: use `columns` typed with `ColumnsType<T>`; render status through a `Tag` with a single color map.

## 6. Routing & Authorization

- Route table lives in `router/`; wrap protected routes in a guard (`RequireAuth`).
- Menu visibility is driven by `permissions` from `/auth/me`. Do **not** trust the frontend alone for security — the backend re-authorizes every write (see backend rules).

## 7. Naming

| Element | Convention | Example |
|---|---|---|
| Component | PascalCase | `ApprovalList` |
| Hook | `useXxx` | `useAuthStore` |
| API module | noun | `approval.ts`, `auth.ts` |
| Type/model | PascalCase/`interface` | `ApprovalInstance` |
| URL param | camelCase | `instanceId` |

## 8. Contract & Consistency

- Keep frontend types aligned with the backend contract. When the backend changes `/v1`, update `types/` and the API module together.
- Never hardcode tenant id or a user identity on the client; take it from the auth context.

## 9. Safety

- Do not log tokens, secrets, or sensitive fields (`console.log` on auth objects is forbidden).
- Handle loading/error states for every async fetch (loading flag + catch).

## 10. Design system

Every screen must follow `.agents/rules/design-system.md`. Visual tokens live in `frontend/src/theme`, shared UI in `frontend/src/components` (`PageContainer`, `StatusTag`). Never hardcode colors/spacing, never re-declare a status color map, never root a page in `<Card>` — use the shared building blocks.
