import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import { RouterProvider } from 'react-router-dom'
import { router } from '@/router'
import { antdTheme } from '@/theme'
import { getMe, initKeycloak, isMock } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import 'antd/dist/reset.css'
import './index.css'

// SSO 模式：整个应用渲染前先初始化 Keycloak 会话（未登录会自动跳转登录页）。
// 放到启动阶段而不是登录页，保证任何路由/刷新后 Keycloak 实例都已就绪
// （否则 logout / token 刷新 / 读取用户信息都会失效）。
async function bootstrap() {
  if (!isMock()) {
    try {
      const authenticated = await initKeycloak()
      if (authenticated) {
        const res = await getMe()
        useAuthStore.getState().setAuth(res.token, res.user, res.permissions)
      }
    } catch {
      // Keycloak 暂不可用时仍渲染应用；后续请求会 401 并回到登录页。
    }
  }

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <ConfigProvider theme={antdTheme}>
        <AntApp>
          <RouterProvider router={router} />
        </AntApp>
      </ConfigProvider>
    </StrictMode>,
  )
}

bootstrap()
