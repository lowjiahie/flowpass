import axios from 'axios'
import { getToken, clearToken } from '@/utils/auth'

// 统一 axios 实例。baseURL 来自环境变量（默认 /api，由 vite proxy 转发到后端网关）。
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000,
})

// 请求：自动携带 Bearer token。
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
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
