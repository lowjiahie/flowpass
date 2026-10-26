import { useEffect, useState } from 'react'
import { Alert, App as AntApp, Form, Input, InputNumber, Modal, Select, Space, Spin, Tag, Typography } from 'antd'
import type { ApproverPreview } from '@/types'
import { createInstance } from '@/api/approval'
import { previewApprovers } from '@/api/decisionRules'
import { getPublishedForm, listTemplates } from '@/api/templates'
import SchemaFields, { parseFormSchema, schemaDefaults } from '@/components/SchemaFields'
import type { FormSchema } from '@/components/SchemaFields'
import { problemMessage } from '@/utils/problem'

interface FormValues {
  templateKey: string
  externalBusinessKey: string
  variables: Record<string, unknown>
}

interface Props {
  open: boolean
  onClose: () => void
  onCreated: () => void
}

/**
 * Start a new approval. The template list and its input form both come from the server: the form is the
 * published JSON Schema of the chosen template, and the approvers shown are the server's own dry-run of the
 * approval matrix — nothing about the template is hardcoded here.
 */
export default function NewApprovalModal({ open, onClose, onCreated }: Props) {
  const { message } = AntApp.useApp()
  const [form] = Form.useForm<FormValues>()
  // null = the caller may not list templates: they type the key instead.
  const [templateKeys, setTemplateKeys] = useState<string[] | null>([])
  const [schema, setSchema] = useState<FormSchema | null>(null)
  const [schemaLoading, setSchemaLoading] = useState(false)
  const [preview, setPreview] = useState<ApproverPreview | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const templateKey = Form.useWatch('templateKey', form)
  const variables = Form.useWatch('variables', form)

  useEffect(() => {
    if (!open) return
    listTemplates()
      .then((all) => setTemplateKeys(all.filter((t) => t.status === 'PUBLISHED').map((t) => t.templateKey)))
      .catch(() => setTemplateKeys(null))
  }, [open])

  // Load the published form of the chosen template.
  useEffect(() => {
    if (!open || !templateKey) {
      setSchema(null)
      return
    }
    let cancelled = false
    setSchemaLoading(true)
    getPublishedForm(templateKey)
      .then((published) => {
        if (cancelled) return
        const parsed = parseFormSchema(published.formSchema)
        setSchema(parsed)
        form.setFieldValue('variables', parsed ? schemaDefaults(parsed) : { currency: 'MYR' })
      })
      .catch(() => {
        if (cancelled) return
        setSchema(null)
        form.setFieldValue('variables', { currency: 'MYR' })
      })
      .finally(() => {
        if (!cancelled) setSchemaLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [open, templateKey, form])

  // Who the approval matrix would ask, using the same resolution as a real start (debounced).
  useEffect(() => {
    if (!open || !templateKey || !variables || variables.amount == null) {
      setPreview(null)
      return
    }
    let cancelled = false
    const timer = setTimeout(() => {
      setPreviewing(true)
      previewApprovers(templateKey, variables)
        .then((result) => {
          if (!cancelled) setPreview(result)
        })
        .catch(() => {
          if (!cancelled) setPreview(null)
        })
        .finally(() => {
          if (!cancelled) setPreviewing(false)
        })
    }, 300)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [open, templateKey, variables])

  const onSubmit = async () => {
    const values = await form.validateFields()
    setSubmitting(true)
    try {
      await createInstance({
        templateKey: values.templateKey,
        externalBusinessKey: values.externalBusinessKey,
        variables: values.variables ?? {},
      })
      message.success('Approval created')
      form.resetFields()
      setPreview(null)
      onCreated()
    } catch (err) {
      message.error(problemMessage(err, 'Create failed'))
    } finally {
      setSubmitting(false)
    }
  }

  const close = () => {
    form.resetFields()
    setPreview(null)
    onClose()
  }

  return (
    <Modal title="New Approval" open={open} confirmLoading={submitting} onOk={onSubmit} onCancel={close} okText="Submit">
      <Form form={form} layout="vertical">
        <Form.Item
          name="templateKey"
          label="Template"
          rules={[{ required: true, message: 'Please select a template' }]}
        >
          {templateKeys === null ? (
            <Input placeholder="Template key" />
          ) : (
            <Select
              placeholder="Select a published template"
              notFoundContent="No published template — publish one first"
              options={templateKeys.map((k) => ({ value: k, label: k }))}
            />
          )}
        </Form.Item>
        <Form.Item
          name="externalBusinessKey"
          label="Business Key"
          rules={[{ required: true, message: 'Please input business key' }]}
        >
          <Input placeholder="e.g. PO-2026-000123" />
        </Form.Item>

        {schemaLoading ? (
          <Spin size="small" />
        ) : schema ? (
          <SchemaFields schema={schema} namePrefix={['variables']} />
        ) : (
          templateKey && (
            <>
              <Form.Item
                name={['variables', 'amount']}
                label="Amount"
                rules={[{ required: true, message: 'Please input amount' }]}
              >
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name={['variables', 'currency']} label="Currency" rules={[{ required: true }]}>
                <Input maxLength={3} />
              </Form.Item>
            </>
          )
        )}

        <Form.Item label="Approvers (resolved by the approval matrix)">
          {variables?.amount == null ? (
            <Typography.Text type="secondary">Enter an amount to preview the approvers.</Typography.Text>
          ) : previewing ? (
            <Spin size="small" />
          ) : preview && preview.rounds.length > 0 ? (
            <Space direction="vertical" size={4}>
              {preview.rounds.map((round, index) => (
                <Space key={`round-${index + 1}`} wrap>
                  <Typography.Text type="secondary">Round {index + 1}</Typography.Text>
                  {round.map((a) => (
                    <Tag key={`${a.type}:${a.id}`}>
                      {a.type} · {a.id}
                    </Tag>
                  ))}
                </Space>
              ))}
            </Space>
          ) : (
            <Alert
              type="warning"
              showIcon
              message="No approvers resolved"
              description={
                preview?.problems.length
                  ? preview.problems.join('; ')
                  : 'This approval would start with no task (manual handling). Add a rule for this template first.'
              }
            />
          )}
        </Form.Item>
      </Form>
    </Modal>
  )
}
