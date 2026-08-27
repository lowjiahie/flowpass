import type { ReactNode } from 'react'
import { Card, Typography } from 'antd'
import { designTokens } from '@/theme'

interface PageContainerProps {
  title: ReactNode
  subtitle?: ReactNode
  extra?: ReactNode
  loading?: boolean
  children?: ReactNode
}

// 统一页面容器：一致的卡片、标题层级、副标题说明、右上操作区、间距。
export default function PageContainer({
  title,
  subtitle,
  extra,
  loading,
  children,
}: PageContainerProps) {
  return (
    <Card title={title} extra={extra} loading={loading}>
      {subtitle && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: designTokens.spacing }}>
          {subtitle}
        </Typography.Paragraph>
      )}
      {children}
    </Card>
  )
}
