import http from '@/api/http'
import type { UserProfile } from '@/types'

export interface AuthResult {
  token: string
  user: UserProfile
  permissions: string[]
}

// 后端就绪前先走 mock，避免前端无法运行。接入 Keycloak + BFF 后置为 false。
// 后端就绪后：POST /auth/login、GET /auth/me（见 saas-iam 方案）。
const USE_MOCK = true

function mockLogin(): AuthResult {
  return {
    token: 'mock-access-token',
    user: { id: 'u_10086', name: 'james first', email: 'james@example.com' },
    permissions: [
      'approval.request.read',
      'approval.request.approve',
      'approval.request.create',
    ],
  }
}

export async function login(username: string, _password: string): Promise<AuthResult> {
  if (USE_MOCK) return mockLogin()
  const { data } = await http.post<AuthResult>('/auth/login', { username, password: _password })
  return data
}

export async function getMe(): Promise<AuthResult> {
  if (USE_MOCK) return mockLogin()
  const { data } = await http.get<AuthResult>('/auth/me')
  return data
}
