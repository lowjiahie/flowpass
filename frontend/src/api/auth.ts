import { keycloak } from '@/auth/keycloak'
import { getToken } from '@/utils/auth'
import type { UserProfile } from '@/types'

export interface AuthResult {
  token: string
  user: UserProfile
  permissions: string[]
}

// 后端/Keycloak 就绪前置 false；本地无 Keycloak 时回退 mock。以 VITE_USE_MOCK=false 开启 SSO。
const USE_MOCK = (import.meta.env.VITE_USE_MOCK ?? 'true') !== 'false'

export function isMock(): boolean {
  return USE_MOCK
}

function mockLogin(): AuthResult {
  return {
    token: 'mock-access-token',
    user: { id: 'u_10086', name: 'james first', email: 'james@example.com' },
    permissions: ['approval.request.read', 'approval.request.approve', 'approval.request.create'],
  }
}

// 从 Keycloak 访问令牌派生前端所需的最小用户/权限信息（不做签名校验——后端会验）。
function parseJwt(token: string): Record<string, unknown> | null {
  try {
    const payload = token.split('.')[1]
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')
    return JSON.parse(atob(padded))
  } catch {
    return null
  }
}

function fromKeycloakToken(): AuthResult {
  const claims = (keycloak.tokenParsed ?? parseJwt(keycloak.token ?? '')) as Record<string, unknown>
  const resourceAccess = (claims.resource_access ?? {}) as Record<string, { roles?: string[] }>
  // The API's authorities are the client roles of `approval-api` (what the backend reads).
  const roles = resourceAccess['approval-api']?.roles ?? resourceAccess['approval-web']?.roles ?? []
  return {
    token: keycloak.token ?? '',
    user: {
      id: String(claims.sub ?? ''),
      name: String(claims.preferred_username ?? claims.name ?? claims.sub ?? 'User'),
      email: typeof claims.email === 'string' ? claims.email : undefined,
    },
    permissions: roles,
  }
}

/** 回到用户当前所在页面（刷新/深链接不应被弹回首页）。登录页本身不作为回调目标。 */
function currentLocation(): string {
  const { origin, pathname, search } = window.location
  const path = pathname === '/login' ? '/' : pathname
  return `${origin}${path}${search}`
}

/** 登录：mock 直接返回；SSO 模式下重定向到 Keycloak（Authorization Code + PKCE）。 */
export async function login(_username?: string, _password?: string): Promise<AuthResult> {
  if (USE_MOCK) return mockLogin()
  await keycloak.login({ redirectUri: currentLocation() })
  return fromKeycloakToken()
}

/**
 * 初始化 Keycloak 会话（应用启动时调用一次）。login-required：未登录即跳转 Keycloak，回调后若已
 * 登录返回 true。缓存 init 的 Promise：StrictMode 会双调用，重复 init 会破坏回调处理。
 */
let initPromise: Promise<boolean> | null = null

export function initKeycloak(): Promise<boolean> {
  if (initPromise) return initPromise
  initPromise = keycloak
    .init({
      onLoad: 'login-required',
      pkceMethod: 'S256',
      redirectUri: currentLocation(),
    })
    .then((authenticated) => authenticated && Boolean(keycloak.token))
    .catch(() => false)
  return initPromise
}

/** 当前登录态（mock 或 Keycloak token）。 */
export async function getMe(): Promise<AuthResult> {
  if (USE_MOCK) return mockLogin()
  return fromKeycloakToken()
}

/** 登出：SSO 模式下走 Keycloak OIDC logout（含 SSO 会话清理），回调到首页。 */
export async function logout(): Promise<void> {
  if (USE_MOCK) return
  await keycloak.logout({ redirectUri: window.location.origin })
}

/** 取当前访问令牌（SSO 模式下临近过期则自动刷新），供 axios 拦截器注入 Authorization。 */
export async function resolveAccessToken(): Promise<string | null> {
  if (USE_MOCK) return getToken()
  try {
    await keycloak.updateToken(30)
    return keycloak.token ?? null
  } catch {
    return null
  }
}
