import { useEffect, useState } from 'react'
import { Card, Col, Row, Statistic } from 'antd'
import { designTokens } from '@/theme'
import PageContainer from '@/components/PageContainer'
import type { ApprovalInstance } from '@/types'
import { listInstances } from '@/api/approval'

export default function Dashboard() {
  const [instances, setInstances] = useState<ApprovalInstance[]>([])

  useEffect(() => {
    listInstances().then(setInstances)
  }, [])

  const count = (status: string) => instances.filter((i) => i.status === status).length

  return (
    <PageContainer title="Dashboard" subtitle="Overview of approval activity.">
      <Row gutter={[16, 16]}>
        <Col span={6}>
          <Card>
            <Statistic title="Running" value={count('RUNNING')} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="Approved"
              value={count('APPROVED')}
              valueStyle={{ color: designTokens.colorSuccess }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="Rejected"
              value={count('REJECTED')}
              valueStyle={{ color: designTokens.colorError }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="Total" value={instances.length} />
          </Card>
        </Col>
      </Row>
    </PageContainer>
  )
}
