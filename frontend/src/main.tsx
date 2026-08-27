import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import { RouterProvider } from 'react-router-dom'
import { router } from '@/router'
import { antdTheme } from '@/theme'
import 'antd/dist/reset.css'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider theme={antdTheme}>
      <AntApp>
        <RouterProvider router={router} />
      </AntApp>
    </ConfigProvider>
  </StrictMode>,
)
