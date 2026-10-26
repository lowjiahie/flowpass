import { useEffect, useState } from 'react'
import { App as AntApp, Select, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { WebhookDelivery } from '@/types'
import { listWebhookDeliveries } from '@/api/admin'
import { problemMessage } from '@/utils/problem'
import PageContainer from '@/components/PageContainer'
import StatusTag from '@/components/StatusTag'

/** Outbound notifications to integrated systems, with their delivery state and next retry. */
export default function WebhookDeliveries() {
  const { message } = AntApp.useApp()
  const [data, setData] = useState<WebhookDelivery[]>([])
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState<string | undefined>(undefined)

  useEffect(() => {
    setLoading(true)
    listWebhookDeliveries({ status, limit: 100 })
      .then(setData)
      .catch((err) => message.error(problemMessage(err, 'Failed to load deliveries')))
      .finally(() => setLoading(false))
  }, [status, message])

  const columns: ColumnsType<WebhookDelivery> = [
    { title: 'Created', dataIndex: 'createdAt' },
    { title: 'Endpoint', dataIndex: 'endpoint', ellipsis: true },
    { title: 'Event', dataIndex: 'eventId', ellipsis: true },
    { title: 'Attempt', dataIndex: 'attempt', width: 90 },
    { title: 'Status', dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
    { title: 'Next retry', dataIndex: 'nextRetryAt', render: (t: string | null) => t ?? '-' },
  ]

  return (
    <PageContainer title="Webhook Deliveries" subtitle="Delivery attempts of outbound events.">
      <Space style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="Status"
          style={{ width: 180 }}
          value={status}
          onChange={setStatus}
          options={['PENDING', 'DELIVERED', 'FAILED'].map((s) => ({ value: s, label: s }))}
        />
      </Space>
      <Table rowKey="id" columns={columns} dataSource={data} loading={loading} pagination={{ pageSize: 15 }} />
    </PageContainer>
  )
}
