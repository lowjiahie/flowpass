import { useEffect, useState } from 'react'
import { App as AntApp, Button, DatePicker, Form, Input, Popconfirm, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { Delegation } from '@/types'
import { createDelegation, listMyDelegations, revokeDelegation } from '@/api/delegations'
import { problemMessage } from '@/utils/problem'
import PageContainer from '@/components/PageContainer'
import StatusTag from '@/components/StatusTag'

interface FormValues {
  delegateId: string
  period: [{ toISOString: () => string }, { toISOString: () => string }]
}

/** Hand my new tasks to someone else for a while (one level, at most 180 days). */
export default function Delegations() {
  const { message } = AntApp.useApp()
  const [form] = Form.useForm<FormValues>()
  const [data, setData] = useState<Delegation[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const load = () => {
    setLoading(true)
    listMyDelegations()
      .then(setData)
      .catch((err) => message.error(problemMessage(err, 'Failed to load delegations')))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const onCreate = async () => {
    const v = await form.validateFields()
    setSaving(true)
    try {
      await createDelegation(v.delegateId.trim(), v.period[1].toISOString(), v.period[0].toISOString())
      message.success('Delegation created')
      form.resetFields()
      load()
    } catch (err) {
      message.error(problemMessage(err, 'Create failed'))
    } finally {
      setSaving(false)
    }
  }

  const onRevoke = async (id: string) => {
    try {
      await revokeDelegation(id)
      message.success('Delegation revoked')
      load()
    } catch (err) {
      message.error(problemMessage(err, 'Revoke failed'))
    }
  }

  const columns: ColumnsType<Delegation> = [
    { title: 'Delegate', dataIndex: 'delegateId' },
    { title: 'From', dataIndex: 'startsAt' },
    { title: 'Until', dataIndex: 'endsAt' },
    {
      title: 'Status',
      dataIndex: 'active',
      render: (active: boolean) => <StatusTag status={active ? 'ACTIVE' : 'INACTIVE'} />,
    },
    {
      title: 'Actions',
      render: (_, d) => (
        <Popconfirm title="Revoke this delegation?" onConfirm={() => onRevoke(d.id)}>
          <Button size="small" danger>
            Revoke
          </Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <PageContainer
      title="My Delegations"
      subtitle="While a delegation is active, tasks that would open for you go to the delegate instead. Tasks already open are not moved."
    >
      <Form form={form} layout="inline" style={{ marginBottom: 16 }}>
        <Form.Item name="delegateId" rules={[{ required: true, message: 'Delegate user id' }]}>
          <Input placeholder="Delegate user id" style={{ width: 220 }} />
        </Form.Item>
        <Form.Item name="period" rules={[{ required: true, message: 'Choose the period' }]}>
          <DatePicker.RangePicker showTime />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" loading={saving} onClick={onCreate}>
              Delegate
            </Button>
          </Space>
        </Form.Item>
      </Form>
      <Table rowKey="id" columns={columns} dataSource={data} loading={loading} pagination={{ pageSize: 10 }} />
    </PageContainer>
  )
}
