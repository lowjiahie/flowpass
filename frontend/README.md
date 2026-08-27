# Flowpass · Approval Admin (Frontend)

React + TypeScript + Vite + Ant Design admin skeleton for the approval workflow service.
It lives in the same repository as the backend (`../backend`), forming a monorepo.

## Stack

| Layer | Choice |
|---|---|
| Build | Vite 5 |
| UI | React 18 + TypeScript + Ant Design 5 |
| State | Zustand (auth) |
| Routing | React Router 6 |
| HTTP | Axios (interceptors + token) |

## Run

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

## Build

```bash
npm run build      # type-check + vite build
npm run preview    # preview production build
```

## Structure

```text
frontend/
├─ src/
│  ├─ api/          # http.ts (axios instance) + auth.ts + approval.ts
│  ├─ layouts/      # AdminLayout (sider menu + header + permission filter)
│  ├─ pages/        # Login, Dashboard, approval/ (list, todo, detail)
│  ├─ router/       # route table + RequireAuth guard
│  ├─ store/        # zustand auth store
│  ├─ types/        # shared domain types
│  └─ utils/        # token persistence
├─ vite.config.ts   # alias @ -> src, /api proxy to backend
└─ .env.development # VITE_API_BASE
```

## Mock / Backend switch

Until the backend is ready, `src/api/auth.ts` and `src/api/approval.ts` run against in-memory mock data.

- Set `USE_MOCK = false` once the backend `/v1` endpoints are live.
- `src/api/auth.ts` maps to BFF endpoints `/auth/login` + `/auth/me` (see `saas-iam` design).
- `src/api/approval.ts` maps to `/v1/approval-instances` + `/v1/tasks`.

## Auth

- Login stores a token via zustand + localStorage.
- `/auth/me` provides `permissions`, used by `AdminLayout` to filter the menu.
- `RequireAuth` guard redirects to `/login` when no token.
