import { useEffect, useState } from 'react'
import { App as AntApp, Input, Select, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { AuditRecord } from '@/types'
import { listAuditRecords } from '@/api/admin'
import { problemMessage } from '@/utils/problem'
import PageContainer from '@/components/PageContainer'
import StatusTag from '@/components/StatusTag'

const ACTIONS = [
  'STARTED',
  'APPROVED',
  'REJECTED',
  'WITHDRAWN',
  'TRANSFERRED',
  'APPROVER_ADDED',
  'RETURNED',
  'DELEGATED',
  'OVERDUE',
  'ESCALATED',
]

/** The append-only ledger of everything that happened to requests of this tenant. */
export default function AuditLog() {
  const { message } = AntApp.useApp()
  const [data, setData] = useState<AuditRecord[]>([])
  const [loading, setLoading] = useState(false)
  const [instanceId, setInstanceId] = useState('')
  const [action, setAction] = useState<string | undefined>(undefined)

  useEffect(() => {
    setLoading(true)
    listAuditRecords({ instanceId: instanceId.trim() || undefined, action, limit: 100 })
      .then(setData)
      .catch((err) => message.error(problemMessage(err, 'Failed to load audit records')))
      .finally(() => setLoading(false))
  }, [instanceId, action, message])

  const columns: ColumnsType<AuditRecord> = [
    { title: 'Time', dataIndex: 'occurredAt' },
    { title: 'Action', dataIndex: 'action', render: (a: string) => <StatusTag status={a} /> },
    { title: 'Actor', render: (_, r) => `${r.actorType}:${r.actorId}` },
    { title: 'Instance', dataIndex: 'instanceId', ellipsis: true },
    { title: 'Task', dataIndex: 'taskId', ellipsis: true, render: (t: string | null) => t ?? '-' },
    { title: 'Comment', dataIndex: 'comment', render: (c: string | null) => c ?? '-' },
  ]

  return (
    <PageContainer title="Audit Log" subtitle="Who did what, and when — oldest first.">
      <Space style={{ marginBottom: 16 }} wrap>
        <Input.Search
          allowClear
          placeholder="Instance id"
          style={{ width: 320 }}
          onSearch={setInstanceId}
        />
        <Select
          allowClear
          placeholder="Action"
          style={{ width: 200 }}
          value={action}
          onChange={setAction}
          options={ACTIONS.map((a) => ({ value: a, label: a }))}
        />
      </Space>
      <Table rowKey="id" columns={columns} dataSource={data} loading={loading} pagination={{ pageSize: 15 }} />
    </PageContainer>
  )
}
