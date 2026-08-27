import { Tag } from 'antd'
import { designTokens } from '@/theme'

// 统一状态标签：状态 → 颜色的单一来源，所有页面共用，避免各自维护色值。
export default function StatusTag({ status }: { status: string }) {
  return <Tag color={designTokens.status[status] ?? 'default'}>{status}</Tag>
}
