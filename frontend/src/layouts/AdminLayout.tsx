import { useMemo } from 'react'
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
  CheckSquareOutlined,
  DashboardOutlined,
  FileSearchOutlined,
  LogoutOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '@/store/auth'

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
  const logout = useAuthStore((s) => s.logout)

  // 菜单按权限过滤（骨架阶段简单映射；后端就绪后可换成动态菜单）。
  const allItems: MenuEntry[] = [
    { key: '/dashboard', icon: <DashboardOutlined />, label: 'Dashboard', required: null },
    { key: '/todo', icon: <CheckSquareOutlined />, label: 'My Todo', required: 'approval.request.approve' },
    { key: '/approvals', icon: <FileSearchOutlined />, label: 'Approvals', required: 'approval.request.read' },
  ]

  const menuItems = useMemo(
    () =>
      allItems
        .filter((i) => !i.required || permissions.includes(i.required))
        .map(({ key, icon, label }) => ({ key, icon, label })),
    [permissions],
  )

  const onLogout = () => {
    logout()
    message.success('Signed out')
    navigate('/login')
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
          selectedKeys={[location.pathname]}
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
