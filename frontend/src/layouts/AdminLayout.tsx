import { useEffect, useMemo } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import {
  App as AntApp,
  Avatar,
  Dropdown,
  Layout,
  Menu,
  Space,
  Typography,
} from 'antd'
import {
  ApartmentOutlined,
  AuditOutlined,
  CheckSquareOutlined,
  DashboardOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  LogoutOutlined,
  SendOutlined,
  SwapOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '@/store/auth'
import { getMe, isMock, logout as ssoLogout } from '@/api/auth'

const { Sider, Header, Content } = Layout

interface MenuEntry {
  key: string
  icon: React.ReactNode
  label: string
  required: string | null
}

export default function AdminLayout() {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const permissions = useAuthStore((s) => s.permissions)
  const user = useAuthStore((s) => s.user)
  const token = useAuthStore((s) => s.token)
  const logout = useAuthStore((s) => s.logout)
  const setAuth = useAuthStore((s) => s.setAuth)

  // 刷新后 token 仍在但 user/permissions 丢失，这里补一次 hydration。
  useEffect(() => {
    if (token && !user) {
      getMe()
        .then((res) => setAuth(res.token, res.user, res.permissions))
        .catch(() => undefined)
    }
  }, [token, user, setAuth])

  // 菜单按权限过滤（骨架阶段简单映射；后端就绪后可换成动态菜单）。
  const allItems: MenuEntry[] = [
    { key: '/dashboard', icon: <DashboardOutlined />, label: 'Dashboard', required: null },
    { key: '/todo', icon: <CheckSquareOutlined />, label: 'My Todo', required: 'approval.request.approve' },
    { key: '/approvals', icon: <FileSearchOutlined />, label: 'Approvals', required: 'approval.request.read' },
    { key: '/delegations', icon: <SwapOutlined />, label: 'Delegations', required: 'approval.request.approve' },
    { key: '/admin/templates', icon: <FileTextOutlined />, label: 'Templates', required: 'approval.template.manage' },
    { key: '/admin/rules', icon: <ApartmentOutlined />, label: 'Approver Rules', required: 'approval.rule.manage' },
    { key: '/admin/audit', icon: <AuditOutlined />, label: 'Audit Log', required: 'approval.request.audit' },
    { key: '/admin/webhooks', icon: <SendOutlined />, label: 'Webhooks', required: 'approval.webhook.manage' },
  ]

  const menuItems = useMemo(
    () =>
      allItems
        .filter((i) => !i.required || permissions.includes(i.required))
        .map(({ key, icon, label }) => ({ key, icon, label })),
    [permissions],
  )

  // A nested route (e.g. /admin/templates/:key) keeps its section highlighted.
  const selectedKeys = allItems
    .map((i) => i.key)
    .filter((k) => location.pathname === k || location.pathname.startsWith(`${k}/`))
    .sort((a, b) => b.length - a.length)
    .slice(0, 1)

  const onLogout = async () => {
    logout()
    message.success('Signed out')
    if (isMock()) {
      navigate('/login')
    } else {
      // SSO 模式：走 Keycloak OIDC logout（清理 SSO 会话后回调首页）。
      await ssoLogout().catch(() => navigate('/login'))
    }
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark" width={220}>
        <div style={{ color: '#fff', padding: 16, fontWeight: 600, fontSize: 16 }}>
          Flowpass
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={selectedKeys}
          items={menuItems}
          onClick={(e) => navigate(e.key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            paddingInline: 24,
          }}
        >
          <Typography.Title level={4} style={{ margin: 0 }}>
            Approval Admin
          </Typography.Title>
          <Space>
            <Dropdown
              menu={{
                items: [
                  { key: 'logout', icon: <LogoutOutlined />, label: 'Logout', onClick: onLogout },
                ],
              }}
            >
              <Space style={{ cursor: 'pointer' }}>
                <Avatar size="small" icon={<UserOutlined />} />
                <span>{user?.name ?? 'User'}</span>
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: '#f5f5f5', borderRadius: 8 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
