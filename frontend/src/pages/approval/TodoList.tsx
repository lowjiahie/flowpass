import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { App as AntApp, Button, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { ApprovalTask } from '@/types'
import { listTodoTasks, approveTask, rejectTask } from '@/api/approval'
import { useAuthStore } from '@/store/auth'
import StatusTag from '@/components/StatusTag'
import PageContainer from '@/components/PageContainer'

export default function TodoList() {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const [data, setData] = useState<ApprovalTask[]>([])
  const [loading, setLoading] = useState(false)
  const [actingId, setActingId] = useState<string | null>(null)

  const load = () => {
    setLoading(true)
    listTodoTasks()
      .then(setData)
      .catch(() => message.error('Failed to load tasks'))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const act = async (task: ApprovalTask, kind: 'approve' | 'reject') => {
    if (!user) {
      message.error('Not signed in')
      return
    }
    setActingId(task.id)
    try {
      const req = {}
      if (kind === 'approve') await approveTask(task.id, req)
      else await rejectTask(task.id, req)
      message.success(kind === 'approve' ? 'Approved' : 'Rejected')
      load()
    } catch {
      message.error(kind === 'approve' ? 'Approve failed' : 'Reject failed')
    } finally {
      setActingId(null)
    }
  }

  const columns: ColumnsType<ApprovalTask> = [
    { title: 'Task', dataIndex: 'name' },
    { title: 'Instance', dataIndex: 'instanceId' },
    {
      title: 'Assignee',
      render: (_, row) => (row.assigneeType === 'ROLE' ? `Role: ${row.assigneeId}` : row.assigneeId),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      render: (s: string) => <StatusTag status={s} />,
    },
    {
      title: 'Actions',
      render: (_, row) => {
        // Convenience only — the server re-checks the assignee (or role membership) on every action.
        const mine = row.assigneeType === 'ROLE' || row.assigneeId === user?.id
        const canAct = mine && (row.status === 'PENDING' || row.status === 'CLAIMED')
        return (
        <Space>
          <Button
            size="small"
            type="primary"
            disabled={!canAct}
            loading={actingId === row.id}
            onClick={() => act(row, 'approve')}
          >
            Approve
          </Button>
          <Button
            size="small"
            danger
            disabled={!canAct}
            loading={actingId === row.id}
            onClick={() => act(row, 'reject')}
          >
            Reject
          </Button>
          <Button size="small" onClick={() => navigate(`/approvals/${row.instanceId}`)}>
            Detail
          </Button>
        </Space>
        )
      },
    },
  ]

  return (
    <PageContainer title="My Todo" subtitle="Tasks assigned to me that require action.">
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{ pageSize: 10 }}
      />
    </PageContainer>
  )
}
