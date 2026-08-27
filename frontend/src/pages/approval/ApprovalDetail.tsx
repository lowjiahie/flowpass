import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Descriptions, Steps } from 'antd'
import type { ApprovalInstance } from '@/types'
import { getInstance } from '@/api/approval'
import StatusTag from '@/components/StatusTag'
import PageContainer from '@/components/PageContainer'

export default function ApprovalDetail() {
  const { id } = useParams<{ id: string }>()
  const [instance, setInstance] = useState<ApprovalInstance | null>(null)

  useEffect(() => {
    if (id) getInstance(id).then(setInstance)
  }, [id])

  if (!instance) return <PageContainer title="Approval" loading />

  return (
    <PageContainer title={`Approval ${instance.id}`}>
      <Descriptions column={2} bordered>
        <Descriptions.Item label="Business Key">{instance.businessKey}</Descriptions.Item>
        <Descriptions.Item label="Template">{instance.templateKey}</Descriptions.Item>
        <Descriptions.Item label="Status">
          <StatusTag status={instance.status} />
        </Descriptions.Item>
        <Descriptions.Item label="Initiator">{instance.initiator}</Descriptions.Item>
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
