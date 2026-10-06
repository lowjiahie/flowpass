import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert,
  App as AntApp,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { ApprovalInstance, ApprovalStatus, Approver } from '@/types'
import { listInstances, createInstance } from '@/api/approval'
import { previewApprovers } from '@/api/decisionRules'
import { useAuthStore } from '@/store/auth'
import StatusTag from '@/components/StatusTag'
import PageContainer from '@/components/PageContainer'

interface CreateFormValues {
  templateKey: string
  externalBusinessKey: string
  amount: number
}

export default function ApprovalList() {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const [form] = Form.useForm<CreateFormValues>()
  const [data, setData] = useState<ApprovalInstance[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>('')
  const [createOpen, setCreateOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [resolved, setResolved] = useState<Approver[]>([])
  const [resolving, setResolving] = useState(false)

  const amount = Form.useWatch('amount', form)
  const templateKey = Form.useWatch('templateKey', form)

  // Preview who the approval matrix will assign, using the same server-side resolution as a real
  // start (debounced so typing the amount does not spam the endpoint).
  useEffect(() => {
    if (!createOpen || !templateKey || amount == null) {
      setResolved([])
      return
    }
    let cancelled = false
    const timer = setTimeout(() => {
      setResolving(true)
      previewApprovers(templateKey, { amount, currency: 'MYR' })
        .then((approvers) => {
          if (!cancelled) setResolved(approvers)
        })
        .catch(() => {
          if (!cancelled) setResolved([])
        })
        .finally(() => {
          if (!cancelled) setResolving(false)
        })
    }, 300)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [createOpen, templateKey, amount])

  const load = () => {
    setLoading(true)
    listInstances()
      .then(setData)
      .catch(() => message.error('Failed to load approvals'))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const onCreate = async () => {
    if (!user) {
      message.error('Not signed in')
      return
    }
    const values = await form.validateFields()
    setSubmitting(true)
    try {
      await createInstance({
        templateKey: values.templateKey,
        externalBusinessKey: values.externalBusinessKey,
        variables: {
          amount: values.amount,
          currency: 'MYR',
        },
      })
      message.success('Approval created')
      setCreateOpen(false)
      form.resetFields()
      load()
    } catch (err) {
      // A refused application lists every offending field (problem+json `errors`); show them all.
      const problems = (err as { response?: { data?: { errors?: { field: string; message: string }[] } } })
        .response?.data?.errors
      message.error(
        problems?.length
          ? problems.map((e) => (e.field ? `${e.field}: ${e.message}` : e.message)).join(' · ')
          : 'Create failed',
      )
    } finally {
      setSubmitting(false)
    }
  }

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
    { title: 'Initiator', dataIndex: 'initiatorId' },
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
      extra={
        <Button type="primary" onClick={() => setCreateOpen(true)}>
          New Approval
        </Button>
      }
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

      <Modal
        title="New Approval"
        open={createOpen}
        confirmLoading={submitting}
        onOk={onCreate}
        onCancel={() => setCreateOpen(false)}
        okText="Submit"
      >
        <Form form={form} layout="vertical" initialValues={{ templateKey: 'purchase_request' }}>
          <Form.Item
            name="templateKey"
            label="Template"
            rules={[{ required: true, message: 'Please select a template' }]}
          >
            <Select
              options={[{ value: 'purchase_request', label: 'Purchase Request' }]}
            />
          </Form.Item>
          <Form.Item
            name="externalBusinessKey"
            label="Business Key"
            rules={[{ required: true, message: 'Please input business key' }]}
          >
            <Input placeholder="e.g. PO-2026-000123" />
          </Form.Item>
          <Form.Item
            name="amount"
            label="Amount (MYR)"
            rules={[{ required: true, message: 'Please input amount' }]}
          >
            <InputNumber min={0} style={{ width: '100%' }} placeholder="0" />
          </Form.Item>
          <Form.Item label="Approvers (resolved by the approval matrix)">
            {amount == null ? (
              <Typography.Text type="secondary">
                Enter an amount to preview the assignee.
              </Typography.Text>
            ) : resolving ? (
              <Spin size="small" />
            ) : resolved.length > 0 ? (
              <Space wrap>
                {resolved.map((a) => (
                  <Tag key={`${a.type}:${a.id}`}>
                    {a.type} · {a.id}
                  </Tag>
                ))}
              </Space>
            ) : (
              <Alert
                type="warning"
                showIcon
                message="No matching rule"
                description="This approval will start with no task (manual handling). Add a rule for this template first."
              />
            )}
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  )
}
