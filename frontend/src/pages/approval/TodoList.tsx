import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { App as AntApp, Button, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { ApprovalTask } from '@/types'
import { listTodoTasks } from '@/api/approval'
import StatusTag from '@/components/StatusTag'
import PageContainer from '@/components/PageContainer'

export default function TodoList() {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const [data, setData] = useState<ApprovalTask[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    setLoading(true)
    listTodoTasks()
      .then(setData)
      .finally(() => setLoading(false))
  }, [])

  const columns: ColumnsType<ApprovalTask> = [
    { title: 'Task', dataIndex: 'name' },
    { title: 'Instance', dataIndex: 'instanceId' },
    { title: 'Assignee', dataIndex: 'assignee' },
    {
      title: 'Status',
      dataIndex: 'status',
      render: (s: string) => <StatusTag status={s} />,
    },
    { title: 'Created', dataIndex: 'createdAt' },
    {
      title: 'Actions',
      render: (_, row) => (
        <Space>
          <Button size="small" type="primary" onClick={() => message.info('Mock: approved')}>
            Approve
          </Button>
          <Button size="small" danger onClick={() => message.info('Mock: rejected')}>
            Reject
          </Button>
          <Button size="small" onClick={() => navigate(`/approvals/${row.instanceId}`)}>
            Detail
          </Button>
        </Space>
      ),
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
