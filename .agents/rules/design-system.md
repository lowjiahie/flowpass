# Design System — Frontend Visual & Interaction Constraints

> Always-on rules that keep every frontend screen visually consistent and modern.
> Companion to `frontend-rules.md` (structure/TS/API) — this file governs *how a screen looks and behaves*.
> `frontend-rules.md` governs *where code lives and how it is written*.

## 0. Non-negotiable

There is **one source of truth for look-and-feel**:

- `frontend/src/theme/index.ts` — color / radius / spacing / status tokens + antd `ThemeConfig`.
- `frontend/src/components/PageContainer.tsx` — every page is wrapped by it (title, subtitle, actions, spacing).
- `frontend/src/components/StatusTag.tsx` — the **single** source for status → color; never re-declare a status color map.

A page that introduces a hardcoded color, a second status color map, or its own page shell **violates the design system** and must be refactored.

## 1. Layout

- Wrap every route-level page in `PageContainer`: `<PageContainer title="..." subtitle="...">`.
- One `PageContainer` per page; one logical card block per concern.
- Content max width is `designTokens.contentMaxWidth` — do not stretch endlessly on ultra-wide screens.
- Use antd `Row/Col` with `gutter={[16,16]}` for grid layouts; keep spacing on the `designTokens.spacing` (16) scale.

## 2. Color

- Only use colors from `designTokens`: `colorPrimary`, `colorSuccess`, `colorWarning`, `colorError`, `colorBgLayout`, `colorText`, `colorTextSecondary`.
- **Never hardcode** hex/rgb in a page or component. The only hex values allowed live in `theme/index.ts`.
- Semantic statuses (Approved/Rejected/Running/… / tasks Pending) are rendered exclusively via `StatusTag`.
- Danger/positive emphasis uses the semantic token color (`colorError` / `colorSuccess`), no ad-hoc greens/reds.

## 3. Typography

- Use antd `Typography` (`Title`, `Paragraph`). Page title is the `PageContainer` `title`; supporting text uses `type="secondary"`.
- Respect the antd font scale; do not hand-set font sizes in `px` unless a token requires it.

## 4. Spacing & Radius

- Spacing follows the 16px scale (`designTokens.spacing`). Use `Space`, `gutter`, or token multiples — no arbitrary magic numbers.
- Radius uses `designTokens.radius` (controls) and `designTokens.radiusCard` (cards); consistent corners everywhere.

## 5. Components (antd)

- Tables: `columns` typed with `ColumnsType<T>`; `rowKey` = stable id; `pagination={{ pageSize: 10 }}` default.
- Status cells → `StatusTag`. Action buttons → `size="small"`, primary for the main action, `danger` for destructive.
- Feedback via `App.useApp()` (`message` / `modal`) — never the static `message` import.
- Loading: pass `loading` to the data component or `PageContainer`; empty/error states are explicit, never blank.

## 6. Feedback & States

Every async fetch must cover: **loading** flag, **success**, and **error** (catch + message). No silent failures.

## 7. Responsive

- Grid uses `Row/Col` with responsive `xs/sm/md/lg/xl` spans where content may overflow.
- Toolbars (filters, actions) collapse gracefully; allow-tight controls get a max width.

## 8. Anti-patterns (never do these)

- ❌ Hardcode a color (`#fff`, `red`, etc.) or inline a `STYLE` color constant in a page.
- ❌ Duplicate `STATUS_COLOR` / status → color maps. Use `StatusTag`.
- ❌ Call `axios` directly in a page, or use static `message`/`Modal`.
- ❌ Start a page with `<Card>` as the root; use `PageContainer`.
- ❌ Use array index as a React key when a stable id exists.

## 9. New-feature checklist (agent MUST pass before PR)

- [ ] Page wrapped in `PageContainer` with `title` (+ `subtitle`/`extra` when relevant).
- [ ] Every enum/status rendered via `StatusTag`.
- [ ] Colors/spacing/radius read from `designTokens`; zero hardcoded values.
- [ ] Loading + error handled.
- [ ] `message`/`modal` via `App.useApp()`.
- [ ] No duplicate of existing tokens/components; reuse over re-invent.
