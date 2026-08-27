import type { ThemeConfig } from 'antd'

// ============================================================
// 设计令牌 / Design Tokens
// ============================================================
// 单一来源：所有颜色、圆角、间距都从这里取，页面不得散落硬编码色值。
// 遵循「现代后台」风格：简洁留白、一致圆角、轻卡片、清晰层级。
// ============================================================

export const designTokens = {
  /*** 色彩（语义化） ***/
  colorPrimary: '#1677ff', // 品牌主色（antd 蓝）
  colorSuccess: '#52c41a',
  colorWarning: '#faad14',
  colorError: '#ff4d4f',
  colorBgLayout: '#f5f7fa', // 页面底色，统一 Canvas
  colorText: '#1f262d', // 主文字（更现代的非纯黑）
  colorTextSecondary: '#667085', // 次要文字/说明

  /*** 几何与间距 ***/
  radius: 8, // 控件圆角
  radiusCard: 12, // 卡片圆角
  spacing: 16, // 基础间距刻度
  contentMaxWidth: 1440, // 内容最大宽，避免超宽屏过度拉伸

  /*** 状态色映射（供 StatusTag / 图标统一使用） ***/
  status: {
    DRAFT: 'default',
    RUNNING: 'processing',
    APPROVED: 'success',
    REJECTED: 'error',
    WITHDRAWN: 'warning',
    TERMINATED: 'default',
    PENDING: 'processing', // 任务状态：待处理
  } as Record<string, string>,
} as const

// antd ConfigProvider 主题。任何页面都要能继承这套 token。
export const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: designTokens.colorPrimary,
    colorBgLayout: designTokens.colorBgLayout,
    colorText: designTokens.colorText,
    colorTextSecondary: designTokens.colorTextSecondary,
    borderRadius: designTokens.radius,
    fontSize: 14,
  },
  components: {
    Card: {
      borderRadiusLG: designTokens.radiusCard,
    },
    Table: {
      headerBg: '#fafafa',
    },
  },
}
