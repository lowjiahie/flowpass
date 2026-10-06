import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { App as AntApp, Button, Descriptions, Steps } from 'antd'
import type { ApprovalInstance } from '@/types'
import { getInstance, withdrawInstance } from '@/api/approval'
import { useAuthStore } from '@/store/auth'
import StatusTag from '@/components/StatusTag'
import PageContainer from '@/components/PageContainer'

export default function ApprovalDetail() {
  const { id } = useParams<{ id: string }>()
  const { message } = AntApp.useApp()
  const user = useAuthStore((s) => s.user)
  const [instance, setInstance] = useState<ApprovalInstance | null>(null)
  const [withdrawing, setWithdrawing] = useState(false)

  useEffect(() => {
    if (!id) return
    getInstance(id).then(setInstance)
  }, [id])

  const onWithdraw = async () => {
    if (!id) return
    setWithdrawing(true)
    try {
      await withdrawInstance(id, {})
      message.success('Withdrawn')
      if (id) {
        getInstance(id).then(setInstance)
      }
    } catch {
      message.error('Withdraw failed')
    } finally {
      setWithdrawing(false)
    }
  }

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

      <Steps
        style={{ marginTop: 24 }}
        direction="vertical"
        current={instance.status === 'APPROVED' ? 2 : instance.status === 'RUNNING' ? 1 : 0}
        items={[
          { title: 'Submitted', description: instance.createdAt },
          { title: 'In Approval' },
          { title: 'Approved' },
        ]}
      />
    </PageContainer>
  )
}
