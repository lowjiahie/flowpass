import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { App as AntApp, Button, Form, Input, Modal, Space, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { Template } from '@/types'
import { createTemplate, disableTemplate, enableTemplate, listTemplates } from '@/api/templates'
import { problemMessage } from '@/utils/problem'
import PageContainer from '@/components/PageContainer'
import StatusTag from '@/components/StatusTag'

export default function TemplateList() {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const [form] = Form.useForm<{ templateKey: string }>()
  const [data, setData] = useState<Template[]>([])
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [actingKey, setActingKey] = useState<string | null>(null)

  const load = () => {
    setLoading(true)
    listTemplates()
      .then(setData)
      .catch((err) => message.error(problemMessage(err, 'Failed to load templates')))
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const onCreate = async () => {
    const { templateKey } = await form.validateFields()
    setSaving(true)
    try {
      const created = await createTemplate(templateKey.trim())
      message.success('Template created')
      setCreateOpen(false)
      form.resetFields()
      navigate(`/admin/templates/${created.templateKey}`)
    } catch (err) {
      message.error(problemMessage(err, 'Create failed'))
    } finally {
      setSaving(false)
    }
  }

  const toggle = async (t: Template) => {
    setActingKey(t.templateKey)
    try {
      if (t.status === 'DISABLED') await enableTemplate(t.templateKey)
      else await disableTemplate(t.templateKey)
      load()
    } catch (err) {
      message.error(problemMessage(err, 'Action failed'))
    } finally {
      setActingKey(null)
    }
  }

  const columns: ColumnsType<Template> = [
    { title: 'Key', dataIndex: 'templateKey' },
    { title: 'Status', dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
    { title: 'Latest version', dataIndex: 'latestVersion', render: (v: number) => (v > 0 ? `v${v}` : '-') },
    {
      title: 'Actions',
      render: (_, t) => (
        <Space>
          <Button size="small" type="primary" onClick={() => navigate(`/admin/templates/${t.templateKey}`)}>
            Open
          </Button>
          {t.status !== 'DRAFT' && (
            <Button size="small" danger={t.status === 'PUBLISHED'} loading={actingKey === t.templateKey} onClick={() => toggle(t)}>
              {t.status === 'DISABLED' ? 'Enable' : 'Disable'}
            </Button>
          )}
        </Space>
      ),
    },
  ]

  return (
    <PageContainer
      title="Approval Templates"
      subtitle="A template is a versioned flow definition plus its input form. Only a published version can be started."
      extra={
        <Button type="primary" onClick={() => setCreateOpen(true)}>
          New template
        </Button>
      }
    >
      <Table rowKey="id" columns={columns} dataSource={data} loading={loading} pagination={{ pageSize: 10 }} />
      <Modal
        title="New template"
        open={createOpen}
        confirmLoading={saving}
        onOk={onCreate}
        onCancel={() => setCreateOpen(false)}
        okText="Create"
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="templateKey"
            label="Template key"
            extra="Becomes the process id of the flow definition; letters, digits and underscores."
            rules={[{ required: true, message: 'Please input a key' }]}
          >
            <Input placeholder="e.g. purchase_request" />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  )
}
