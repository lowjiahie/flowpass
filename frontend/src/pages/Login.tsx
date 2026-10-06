import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { App as AntApp, Button, Card, Form, Input, Typography } from 'antd'
import { UserOutlined, LockOutlined } from '@ant-design/icons'
import { login, isMock } from '@/api/auth'
import { useAuthStore } from '@/store/auth'

export default function Login() {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const setAuth = useAuthStore((s) => s.setAuth)
  const [loading, setLoading] = useState(false)

  // SSO 模式下登录由应用启动时的 main.tsx 初始化流程接管（login-required 会自动跳转），
  // 这里只在 Keycloak 初始化失败时作为手动入口。
  const onMockFinish = async (values: { username: string; password: string }) => {
    setLoading(true)
    try {
      const res = await login(values.username, values.password)
      setAuth(res.token, res.user, res.permissions)
      message.success('Welcome back')
      navigate('/')
    } catch {
      message.error('Login failed')
    } finally {
      setLoading(false)
    }
  }

  if (!isMock()) {
    return (
      <div
        style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          minHeight: '100vh',
          background: '#f0f2f5',
        }}
      >
        <Card style={{ width: 380, textAlign: 'center' }}>
          <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 8 }}>
            Flowpass
          </Typography.Title>
          <Typography.Paragraph type="secondary" style={{ textAlign: 'center' }}>
            Sign in with the platform SSO
          </Typography.Paragraph>
          <Button
            type="primary"
            block
            loading={loading}
            onClick={async () => {
              setLoading(true)
              await login().catch(() => undefined)
            }}
          >
            Sign in with Keycloak
          </Button>
          <Typography.Paragraph
            type="secondary"
            style={{ textAlign: 'center', marginTop: 16, fontSize: 12 }}
          >
            SSO demo user: demo / demo
          </Typography.Paragraph>
        </Card>
      </div>
    )
  }

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '100vh',
        background: '#f0f2f5',
      }}
    >
      <Card style={{ width: 380 }}>
        <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 8 }}>
          Flowpass
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center' }}>
          Sign in to the Approval Admin
        </Typography.Paragraph>
        <Form onFinish={onMockFinish} layout="vertical">
          <Form.Item
            name="username"
            rules={[{ required: true, message: 'Please input username' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="Username" />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: 'Please input password' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="Password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            Sign in
          </Button>
        </Form>
        <Typography.Paragraph
          type="secondary"
          style={{ textAlign: 'center', marginTop: 16, fontSize: 12 }}
        >
          Mock mode: any credentials will work until the backend is ready.
        </Typography.Paragraph>
      </Card>
    </div>
  )
}
