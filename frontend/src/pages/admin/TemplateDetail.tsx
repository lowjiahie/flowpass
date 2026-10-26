import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  Alert,
  App as AntApp,
  Button,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tabs,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type {
  OverdueAction,
  SlaPolicy,
  TemplateDetail as TemplateDetailModel,
  TemplateVersion,
  TemplateVersionDetail,
} from '@/types'
import {
  deleteSla,
  getSla,
  getTemplate,
  getVersion,
  listVersions,
  publishTemplate,
  saveDraft,
  saveFormSchema,
  saveSla,
} from '@/api/templates'
import { problemMessage } from '@/utils/problem'
import PageContainer from '@/components/PageContainer'
import StatusTag from '@/components/StatusTag'
import RulesPanel from '@/pages/admin/RulesPanel'
import BpmnDiagram from '@/components/BpmnDiagram'
import BpmnEditor from '@/components/BpmnEditor'
import { buildApprovalFlow, defaultRoundName, MAX_ROUNDS, readRounds } from '@/utils/approvalFlow'
import type { FlowRound } from '@/utils/approvalFlow'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'

const OVERDUE_OPTIONS: { value: OverdueAction; label: string }[] = [
  { value: 'NOTIFY_ONLY', label: 'Only notify' },
  { value: 'ESCALATE_TO_MANAGER', label: "Escalate to the assignee's manager" },
  { value: 'ESCALATE_TO_USER', label: 'Escalate to a fixed user' },
]

const CODE_STYLE = { fontFamily: 'monospace' } as const

/** Pretty-print a JSON string for editing; leaves it untouched when it is not valid JSON. */
function prettyJson(raw: string | null): string {
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

interface SlaFormValues {
  dueAfterHours: number
  remindBeforeHours?: number | null
  onOverdue: OverdueAction
  escalateToUser?: string
}

export default function TemplateDetail() {
  const { key = '' } = useParams<{ key: string }>()
  const { message } = AntApp.useApp()
  const [template, setTemplate] = useState<TemplateDetailModel | null>(null)
  const [bpmn, setBpmn] = useState('')
  const [rounds, setRounds] = useState<FlowRound[]>([{ name: defaultRoundName(1) }])
  const [schemaText, setSchemaText] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [versions, setVersions] = useState<TemplateVersion[]>([])
  const [viewing, setViewing] = useState<TemplateVersionDetail | null>(null)
  const [sla, setSla] = useState<SlaPolicy | null>(null)
  const [slaForm] = Form.useForm<SlaFormValues>()
  const onOverdue = Form.useWatch('onOverdue', slaForm)

  const apply = useCallback((t: TemplateDetailModel) => {
    setTemplate(t)
    const known = readRounds(t.draftBpmn ?? '')
    if (known) setRounds(known)
    // A template without a flow yet starts from the standard one-round flow (unsaved until "Save draft").
    setBpmn(t.draftBpmn ?? buildApprovalFlow(t.templateKey, [{ name: defaultRoundName(1) }]))
    setSchemaText(prettyJson(t.draftFormSchema))
  }, [])

  const loadAll = useCallback(() => {
    getTemplate(key)
      .then(apply)
      .catch((err) => message.error(problemMessage(err, 'Failed to load template')))
    listVersions(key)
      .then(setVersions)
      .catch(() => setVersions([]))
    getSla(key)
      .then((policy) => {
        setSla(policy)
        slaForm.setFieldsValue(
          policy
            ? { ...policy, escalateToUser: policy.escalateToUser ?? undefined }
            : { dueAfterHours: 24, remindBeforeHours: null, onOverdue: 'NOTIFY_ONLY', escalateToUser: undefined },
        )
      })
      .catch(() => setSla(null))
  }, [key, apply, message, slaForm])

  useEffect(loadAll, [loadAll])

  const run = async (name: string, action: () => Promise<void>) => {
    setBusy(name)
    try {
      await action()
    } catch (err) {
      message.error(problemMessage(err, `${name} failed`))
    } finally {
      setBusy(null)
    }
  }

  const onSaveDraft = () =>
    run('Save draft', async () => {
      apply(await saveDraft(key, bpmn))
      message.success('Draft saved')
    })

  const generateFlow = () => {
    setBpmn(buildApprovalFlow(key, rounds))
    message.success('Flow generated — review the diagram, then save the draft')
  }

  const updateRound = (index: number, name: string) =>
    setRounds((current) => current.map((r, i) => (i === index ? { name } : r)))

  const onSaveSchema = () =>
    run('Save form', async () => {
      let parsed: Record<string, unknown> | null = null
      if (schemaText.trim()) {
        try {
          parsed = JSON.parse(schemaText) as Record<string, unknown>
        } catch {
          throw new Error('The form schema is not valid JSON')
        }
      }
      apply(await saveFormSchema(key, parsed))
      message.success(parsed ? 'Form schema saved' : 'Form schema cleared')
    })

  const onPublish = () =>
    run('Publish', async () => {
      const published = await publishTemplate(key)
      message.success(`Published v${published.latestVersion}`)
      loadAll()
    })

  const onSaveSla = async () => {
    const v = await slaForm.validateFields()
    await run('Save SLA', async () => {
      setSla(
        await saveSla(key, {
          dueAfterHours: v.dueAfterHours,
          remindBeforeHours: v.remindBeforeHours ?? null,
          onOverdue: v.onOverdue,
          escalateToUser: v.onOverdue === 'ESCALATE_TO_USER' ? v.escalateToUser : null,
        }),
      )
      message.success('SLA saved — it applies to tasks opened from now on')
    })
  }

  const onDeleteSla = () =>
    run('Remove SLA', async () => {
      await deleteSla(key)
      setSla(null)
      message.success('SLA removed')
    })

  const openVersion = (version: number) =>
    run('Load version', async () => setViewing(await getVersion(key, version)))

  const useAsDraft = () =>
    run('Copy to draft', async () => {
      if (!viewing) return
      await saveDraft(key, viewing.bpmnXml)
      apply(
        await saveFormSchema(key, viewing.formSchema ? (JSON.parse(viewing.formSchema) as Record<string, unknown>) : null),
      )
      setViewing(null)
      message.success(`Draft replaced with v${viewing.version}`)
    })

  const versionColumns: ColumnsType<TemplateVersion> = [
    { title: 'Version', dataIndex: 'version', render: (v: number) => `v${v}` },
    { title: 'Published at', dataIndex: 'publishedAt' },
    { title: 'By', render: (_, v) => `${v.publishedByType}:${v.publishedById}` },
    { title: 'Checksum', dataIndex: 'checksum', ellipsis: true },
    {
      title: 'Actions',
      render: (_, v) => (
        <Button size="small" onClick={() => openVersion(v.version)}>
          View
        </Button>
      ),
    },
  ]

  if (!template) return <PageContainer title={`Template ${key}`} loading />

  return (
    <PageContainer
      title={`Template ${template.templateKey}`}
      subtitle="Edit the draft, then publish to create an immutable version. Running requests keep the version they started on."
      extra={
        <Popconfirm title="Publish the current draft as a new version?" onConfirm={onPublish}>
          <Button type="primary" loading={busy === 'Publish'}>
            Publish
          </Button>
        </Popconfirm>
      }
    >
      <Descriptions column={3} style={{ marginBottom: 16 }}>
        <Descriptions.Item label="Status">
          <StatusTag status={template.status} />
        </Descriptions.Item>
        <Descriptions.Item label="Latest version">
          {template.latestVersion > 0 ? `v${template.latestVersion}` : 'not published'}
        </Descriptions.Item>
      </Descriptions>

      <Tabs
        items={[
          {
            key: 'flow',
            label: 'Flow',
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size="middle">
                <Alert
                  type="info"
                  showIcon
                  message="Draw the flow on the canvas, or start from a generated one. Saving checks that every rejection ends as rejected and every round is entered only when it has approvers."
                />
                <div>
                  <Typography.Text strong>Quick start: generate a standard flow</Typography.Text>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
                    Rounds open one after another; within a round every approver must approve. Generating replaces
                    the drawing below, which you can then adjust.
                  </Typography.Paragraph>
                  <Space direction="vertical" style={{ width: '100%' }}>
                    {rounds.map((round, index) => (
                      <Space key={`round-${index + 1}`} style={{ display: 'flex' }}>
                        <Typography.Text type="secondary" style={{ width: 70, display: 'inline-block' }}>
                          Round {index + 1}
                        </Typography.Text>
                        <Input
                          value={round.name}
                          maxLength={60}
                          style={{ width: 320 }}
                          onChange={(e) => updateRound(index, e.target.value)}
                        />
                        {rounds.length > 1 && (
                          <Button
                            icon={<DeleteOutlined />}
                            onClick={() => setRounds((c) => c.filter((_, i) => i !== index))}
                          />
                        )}
                      </Space>
                    ))}
                    <Space>
                      <Button
                        icon={<PlusOutlined />}
                        disabled={rounds.length >= MAX_ROUNDS}
                        onClick={() =>
                          setRounds((c) => [...c, { name: defaultRoundName(c.length + 1) }])
                        }
                      >
                        Add round
                      </Button>
                      <Popconfirm
                        title="Replace the flow in the editor with one generated from these rounds?"
                        onConfirm={generateFlow}
                      >
                        <Button type="primary" ghost>
                          Generate flow
                        </Button>
                      </Popconfirm>
                    </Space>
                  </Space>
                </div>
                <Tabs
                  type="card"
                  items={[
                    { key: 'diagram', label: 'Draw', children: <BpmnEditor xml={bpmn} onChange={setBpmn} /> },
                    {
                      key: 'xml',
                      label: 'XML',
                      children: (
                        <Input.TextArea
                          rows={22}
                          value={bpmn}
                          onChange={(e) => setBpmn(e.target.value)}
                          style={CODE_STYLE}
                          placeholder="BPMN 2.0 XML"
                        />
                      ),
                    },
                  ]}
                />
                <Button type="primary" loading={busy === 'Save draft'} onClick={onSaveDraft} disabled={!bpmn.trim()}>
                  Save draft
                </Button>
              </Space>
            ),
          },
          {
            key: 'form',
            label: 'Input form (JSON Schema)',
            children: (
              <Space direction="vertical" style={{ width: '100%' }}>
                <Alert
                  type="info"
                  showIcon
                  message="The schema drives the New Approval form and is enforced on the server. amount must be a number and currency a 3-letter code."
                />
                <Input.TextArea
                  rows={18}
                  value={schemaText}
                  onChange={(e) => setSchemaText(e.target.value)}
                  style={CODE_STYLE}
                  placeholder='{"type":"object","required":["amount","currency"],"properties":{...}}'
                />
                <Space>
                  <Button type="primary" loading={busy === 'Save form'} onClick={onSaveSchema}>
                    {schemaText.trim() ? 'Save form schema' : 'Clear form schema'}
                  </Button>
                </Space>
              </Space>
            ),
          },
          {
            key: 'versions',
            label: `Versions (${versions.length})`,
            children: (
              <Table rowKey="version" columns={versionColumns} dataSource={versions} pagination={{ pageSize: 10 }} />
            ),
          },
          {
            key: 'rules',
            label: 'Approver rules',
            children: <RulesPanel templateKey={key} />,
          },
          {
            key: 'sla',
            label: 'Deadlines (SLA)',
            children: (
              <Form form={slaForm} layout="vertical" style={{ maxWidth: 480 }} initialValues={{ onOverdue: 'NOTIFY_ONLY' }}>
                <Typography.Paragraph type="secondary">
                  {sla ? 'A policy is set.' : 'No policy: tasks of this template have no deadline.'}
                </Typography.Paragraph>
                <Form.Item name="dueAfterHours" label="Due after (hours)" rules={[{ required: true }]}>
                  <InputNumber min={1} max={8760} style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item name="remindBeforeHours" label="Remind before the deadline (hours, optional)">
                  <InputNumber min={1} max={8760} style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item name="onOverdue" label="When overdue" rules={[{ required: true }]}>
                  <Select options={OVERDUE_OPTIONS} />
                </Form.Item>
                {onOverdue === 'ESCALATE_TO_USER' && (
                  <Form.Item name="escalateToUser" label="Escalate to user id" rules={[{ required: true }]}>
                    <Input />
                  </Form.Item>
                )}
                <Space>
                  <Button type="primary" loading={busy === 'Save SLA'} onClick={onSaveSla}>
                    Save
                  </Button>
                  {sla && (
                    <Popconfirm title="Remove the deadline policy?" onConfirm={onDeleteSla}>
                      <Button danger loading={busy === 'Remove SLA'}>
                        Remove
                      </Button>
                    </Popconfirm>
                  )}
                </Space>
              </Form>
            ),
          },
        ]}
      />

      <Modal
        title={viewing ? `Version v${viewing.version}` : ''}
        open={viewing !== null}
        width={860}
        onCancel={() => setViewing(null)}
        footer={[
          <Button key="close" onClick={() => setViewing(null)}>
            Close
          </Button>,
          <Popconfirm key="copy" title="Replace the current draft with this version?" onConfirm={useAsDraft}>
            <Button type="primary" loading={busy === 'Copy to draft'}>
              Use as draft
            </Button>
          </Popconfirm>,
        ]}
      >
        {viewing && (
          <Tabs
            items={[
              {
                key: 'diagram',
                label: 'Diagram',
                children: <BpmnDiagram xml={viewing.bpmnXml} height={420} />,
              },
              {
                key: 'bpmn',
                label: 'XML',
                children: <Input.TextArea rows={18} readOnly value={viewing.bpmnXml} style={CODE_STYLE} />,
              },
              {
                key: 'schema',
                label: 'Form',
                children: (
                  <Input.TextArea
                    rows={18}
                    readOnly
                    value={prettyJson(viewing.formSchema) || '(no form schema)'}
                    style={CODE_STYLE}
                  />
                ),
              },
            ]}
          />
        )}
      </Modal>
    </PageContainer>
  )
}
