import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { App as AntApp, Button, Descriptions, Table, Timeline, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { ApprovalInstance, ApprovalTask, AuditRecord } from '@/types'
import { getInstance, listTasksByInstance, withdrawInstance } from '@/api/approval'
import { listAuditRecords } from '@/api/admin'
import { useAuthStore } from '@/store/auth'
import { problemMessage } from '@/utils/problem'
import StatusTag from '@/components/StatusTag'
import PageContainer from '@/components/PageContainer'

export default function ApprovalDetail() {
  const { id } = useParams<{ id: string }>()
  const { message } = AntApp.useApp()
  const user = useAuthStore((s) => s.user)
  const permissions = useAuthStore((s) => s.permissions)
  const [instance, setInstance] = useState<ApprovalInstance | null>(null)
  const [tasks, setTasks] = useState<ApprovalTask[]>([])
  const [history, setHistory] = useState<AuditRecord[]>([])
  const [withdrawing, setWithdrawing] = useState(false)
  const canSeeAudit = permissions.includes('approval.request.audit')

  const load = useCallback(() => {
    if (!id) return
    getInstance(id)
      .then(setInstance)
      .catch((err) => message.error(problemMessage(err, 'Failed to load approval')))
    listTasksByInstance(id)
      .then(setTasks)
      .catch(() => setTasks([]))
    if (canSeeAudit) {
      listAuditRecords({ instanceId: id, limit: 100 })
        .then(setHistory)
        .catch(() => setHistory([]))
    }
  }, [id, canSeeAudit, message])

  useEffect(load, [load])

  const onWithdraw = async () => {
    if (!id) return
    setWithdrawing(true)
    try {
      await withdrawInstance(id, {})
      message.success('Withdrawn')
      load()
    } catch (err) {
      message.error(problemMessage(err, 'Withdraw failed'))
    } finally {
      setWithdrawing(false)
    }
  }

  const taskColumns: ColumnsType<ApprovalTask> = [
    { title: 'Task', dataIndex: 'name' },
    {
      title: 'Assignee',
      render: (_, t) => (t.assigneeType === 'ROLE' ? `Role: ${t.assigneeId}` : t.assigneeId),
    },
    { title: 'Due', dataIndex: 'dueAt', render: (d?: string | null) => (d ? new Date(d).toLocaleString() : '-') },
    { title: 'Status', dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
  ]

  if (!instance) return <PageContainer title="Approval" loading />

  return (
    <PageContainer
      title={`Approval ${instance.id}`}
      extra={
        instance.status === 'RUNNING' && user?.id === instance.initiatorId ? (
          <Button danger loading={withdrawing} onClick={onWithdraw}>
            Withdraw
          </Button>
        ) : undefined
      }
    >
      <Descriptions column={2} bordered>
        <Descriptions.Item label="Business Key">{instance.businessKey}</Descriptions.Item>
        <Descriptions.Item label="Template">{instance.templateKey}</Descriptions.Item>
        <Descriptions.Item label="Status">
          <StatusTag status={instance.status} />
        </Descriptions.Item>
        <Descriptions.Item label="Initiator">{instance.initiatorId}</Descriptions.Item>
        <Descriptions.Item label="Amount">
          {instance.amount != null
            ? `${instance.currency ?? ''} ${instance.amount.toLocaleString()}`
            : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="Created">{instance.createdAt}</Descriptions.Item>
        <Descriptions.Item label="Definition Version">
          {instance.definitionVersion ?? '-'}
        </Descriptions.Item>
      </Descriptions>

      <Typography.Title level={5} style={{ marginTop: 24 }}>
        Tasks
      </Typography.Title>
      <Table rowKey="id" columns={taskColumns} dataSource={tasks} pagination={false} size="small" />

      {canSeeAudit && (
        <>
          <Typography.Title level={5} style={{ marginTop: 24 }}>
            History
          </Typography.Title>
          <Timeline
            items={history.map((r) => ({
              key: r.id,
              children: (
                <>
                  <StatusTag status={r.action} /> {r.actorType}:{r.actorId}{' '}
                  <Typography.Text type="secondary">{new Date(r.occurredAt).toLocaleString()}</Typography.Text>
                  {r.comment && <div>{r.comment}</div>}
                </>
              ),
            }))}
          />
        </>
      )}
    </PageContainer>
  )
}
