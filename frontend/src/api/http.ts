import axios from 'axios'
import { clearToken } from '@/utils/auth'
import { isMock, resolveAccessToken } from '@/api/auth'
import { useAuthStore } from '@/store/auth'

// 统一 axios 实例。baseURL 来自环境变量（默认 /api，由 vite proxy 转发到后端网关）。
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000,
})

// 请求：自动携带 Bearer token（mock 或 Keycloak access token，临近过期自动刷新）。
http.interceptors.request.use(async (config) => {
  const token = await resolveAccessToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  // Mock mode talks to a backend started with the `dev` profile, which has no IdP: it reads the
  // acting user from X-Actor-Id. Never sent in SSO mode — there the server trusts the token only.
  if (isMock()) {
    const user = useAuthStore.getState().user
    if (user) config.headers.set('X-Actor-Id', user.id)
  }
  return config
})

// 响应：401 清除登录态并跳转登录页；其余错误统一抛出。
http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      clearToken()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  },
)

export default http
