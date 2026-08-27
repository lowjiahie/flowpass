import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Input, Select, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { ApprovalInstance, ApprovalStatus } from '@/types'
import { listInstances } from '@/api/approval'
import StatusTag from '@/components/StatusTag'
import PageContainer from '@/components/PageContainer'

export default function ApprovalList() {
  const navigate = useNavigate()
  const [data, setData] = useState<ApprovalInstance[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>('')

  useEffect(() => {
    setLoading(true)
    listInstances()
      .then(setData)
      .finally(() => setLoading(false))
  }, [])

  const filtered = useMemo(
    () =>
      data.filter(
        (i) =>
          (!status || i.status === status) &&
          (!keyword ||
            i.businessKey.toLowerCase().includes(keyword.toLowerCase()) ||
            i.templateKey.toLowerCase().includes(keyword.toLowerCase())),
      ),
    [data, keyword, status],
  )

  const columns: ColumnsType<ApprovalInstance> = [
    { title: 'Business Key', dataIndex: 'businessKey' },
    { title: 'Template', dataIndex: 'templateKey' },
    {
      title: 'Status',
      dataIndex: 'status',
      render: (s: ApprovalStatus) => <StatusTag status={s} />,
    },
    { title: 'Initiator', dataIndex: 'initiator' },
    {
      title: 'Amount',
      render: (_, i) =>
        i.amount != null ? `${i.currency ?? ''} ${i.amount.toLocaleString()}` : '-',
    },
    { title: 'Created', dataIndex: 'createdAt' },
  ]

  return (
    <PageContainer
      title="Approval Instances"
      subtitle="Query and manage approval instances across all tenants."
    >
      <Space style={{ marginBottom: 16 }}>
        <Input.Search
          placeholder="Search business key / template"
          allowClear
          onChange={(e) => setKeyword(e.target.value)}
          style={{ width: 280 }}
        />
        <Select
          allowClear
          placeholder="Status"
          style={{ width: 160 }}
          onChange={setStatus as (v: string) => void}
          options={[
            { value: 'RUNNING', label: 'Running' },
            { value: 'APPROVED', label: 'Approved' },
            { value: 'REJECTED', label: 'Rejected' },
            { value: 'WITHDRAWN', label: 'Withdrawn' },
          ]}
        />
      </Space>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={filtered}
        loading={loading}
        pagination={{ pageSize: 10 }}
        onRow={(record) => ({
          onClick: () => navigate(`/approvals/${record.id}`),
          style: { cursor: 'pointer' },
        })}
      />
    </PageContainer>
  )
}
