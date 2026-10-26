import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { App as AntApp, Button, Input, Modal, Popconfirm, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { ApprovalTask } from '@/types'
import { addApprover, listTodoTasks, approveTask, rejectTask, returnTask, transferTask } from '@/api/approval'
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
  // Transfer / add-sign need a user id: one small dialog serves both.
  const [dialog, setDialog] = useState<{ kind: 'transfer' | 'add'; task: ApprovalTask } | null>(null)
  const [targetUser, setTargetUser] = useState('')

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

  // Shows the server's reason (problem+json `detail`) when it refuses, e.g. "already involved in this request".
  const reason = (err: unknown, fallback: string) =>
    (err as { response?: { data?: { detail?: string } } }).response?.data?.detail ?? fallback

  const submitDialog = async () => {
    if (!dialog || !targetUser.trim()) return
    const { kind, task } = dialog
    setActingId(task.id)
    try {
      if (kind === 'transfer') await transferTask(task.id, targetUser.trim())
      else await addApprover(task.id, targetUser.trim())
      message.success(kind === 'transfer' ? 'Transferred' : 'Approver added')
      setDialog(null)
      setTargetUser('')
      load()
    } catch (err) {
      message.error(reason(err, kind === 'transfer' ? 'Transfer failed' : 'Add approver failed'))
    } finally {
      setActingId(null)
    }
  }

  const sendBack = async (task: ApprovalTask) => {
    setActingId(task.id)
    try {
      await returnTask(task.id)
      message.success('Returned to the previous round')
      load()
    } catch (err) {
      message.error(reason(err, 'Return failed'))
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
      title: 'Due',
      dataIndex: 'dueAt',
      render: (d?: string | null) => {
        if (!d) return '—'
        const overdue = new Date(d).getTime() < Date.now()
        return <span style={overdue ? { color: '#cf1322' } : undefined}>{new Date(d).toLocaleString()}</span>
      },
    },
    {
      title: 'Status',
      dataIndex: 'status',
      render: (s: string) => <StatusTag status={s} />,
    },
    {
      title: 'Actions',
      render: (_, row) => {
        // Convenience only â€” the server re-checks the assignee (or role membership) on every action.
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
          <Button
            size="small"
            disabled={!canAct}
            onClick={() => setDialog({ kind: 'transfer', task: row })}
          >
            Transfer
          </Button>
          <Button
            size="small"
            disabled={!canAct}
            onClick={() => setDialog({ kind: 'add', task: row })}
          >
            Add approver
          </Button>
          <Popconfirm
            title="Send back to the previous round?"
            description="Approvals already given in this round are discarded."
            disabled={!canAct}
            onConfirm={() => sendBack(row)}
          >
            <Button size="small" disabled={!canAct} loading={actingId === row.id}>
              Return
            </Button>
          </Popconfirm>
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
      <Modal
        open={dialog !== null}
        title={dialog?.kind === 'transfer' ? 'Transfer task to' : 'Ask another user to approve as well'}
        okText={dialog?.kind === 'transfer' ? 'Transfer' : 'Add'}
        okButtonProps={{ disabled: !targetUser.trim() }}
        confirmLoading={actingId !== null && actingId === dialog?.task.id}
        onOk={submitDialog}
        onCancel={() => {
          setDialog(null)
          setTargetUser('')
        }}
      >
        <Input
          autoFocus
          placeholder="User id"
          value={targetUser}
          onChange={(e) => setTargetUser(e.target.value)}
          onPressEnter={submitDialog}
        />
      </Modal>
    </PageContainer>
  )
}
