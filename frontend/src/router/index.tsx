import { createBrowserRouter, Navigate, Outlet } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import AdminLayout from '@/layouts/AdminLayout'
import Login from '@/pages/Login'
import Dashboard from '@/pages/Dashboard'
import TodoList from '@/pages/approval/TodoList'
import ApprovalList from '@/pages/approval/ApprovalList'
import ApprovalDetail from '@/pages/approval/ApprovalDetail'
import Delegations from '@/pages/approval/Delegations'
import TemplateList from '@/pages/admin/TemplateList'
import TemplateDetail from '@/pages/admin/TemplateDetail'
import RuleList from '@/pages/admin/RuleList'
import AuditLog from '@/pages/admin/AuditLog'
import WebhookDeliveries from '@/pages/admin/WebhookDeliveries'

// 路由守卫：未登录时跳转登录页。
function RequireAuth() {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <Outlet />
}

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  {
    path: '/',
    element: <RequireAuth />,
    children: [
      {
        element: <AdminLayout />,
        children: [
          { index: true, element: <Navigate to="/dashboard" replace /> },
          { path: 'dashboard', element: <Dashboard /> },
          { path: 'todo', element: <TodoList /> },
          { path: 'approvals', element: <ApprovalList /> },
          { path: 'approvals/:id', element: <ApprovalDetail /> },
          { path: 'delegations', element: <Delegations /> },
          { path: 'admin/templates', element: <TemplateList /> },
          { path: 'admin/templates/:key', element: <TemplateDetail /> },
          { path: 'admin/rules', element: <RuleList /> },
          { path: 'admin/audit', element: <AuditLog /> },
          { path: 'admin/webhooks', element: <WebhookDeliveries /> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
