import { useEffect, useState } from 'react'
import { App as AntApp, Button, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons'
import type { Approver, CreateDecisionRuleRequest, DecisionRule } from '@/types'
import { createRule, listRules } from '@/api/decisionRules'
import { problemMessage } from '@/utils/problem'

const APPROVER_TYPES = [
  { value: 'USER', label: 'User (id = user id)' },
  { value: 'ROLE', label: 'Role (id = role name)' },
  { value: 'INITIATOR_MANAGER', label: 'Initiator manager (id = 1 for direct, 2 for next level…)' },
  { value: 'DEPARTMENT_HEAD', label: 'Department head (id = initiator)' },
  { value: 'FORM_FIELD', label: 'Form field (id = field name holding a user id)' },
]

interface RuleFormValues {
  templateKey: string
  priority: number
  amountMin?: number
  amountMax?: number
  expression?: string
  fieldMatches?: string
  approvers: { round: number; type: string; id: string }[]
}

/** `field=value` per line → map. Blank lines are ignored. */
function parseFieldMatches(raw: string | undefined): Record<string, string> | undefined {
  const entries = (raw ?? '')
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => {
      const at = l.indexOf('=')
      return at > 0 ? ([l.slice(0, at).trim(), l.slice(at + 1).trim()] as const) : null
    })
    .filter((e): e is readonly [string, string] => e !== null)
  return entries.length > 0 ? Object.fromEntries(entries) : undefined
}

function describeCondition(rule: DecisionRule): string {
  const parts: string[] = []
  if (rule.amountMin != null || rule.amountMax != null) {
    parts.push(`amount ${rule.amountMin ?? '-∞'} … ${rule.amountMax ?? '+∞'}`)
  }
  for (const [k, v] of Object.entries(rule.fieldMatches ?? {})) parts.push(`${k} = ${v}`)
  if (rule.expression) parts.push(rule.expression)
  return parts.length > 0 ? parts.join(' AND ') : 'always (catch-all)'
}

function groupByRound(approvers: Approver[]): Approver[][] {
  const rounds = new Map<number, Approver[]>()
  for (const a of approvers) {
    const round = a.round ?? 1
    rounds.set(round, [...(rounds.get(round) ?? []), a])
  }
  return [...rounds.entries()].sort((x, y) => x[0] - y[0]).map(([, list]) => list)
}

interface Props {
  /** Fixed template (template detail) or undefined to show and create for every template. */
  templateKey?: string
  /** Template keys offered when no fixed template is given. */
  templateKeys?: string[]
}

/** The approval matrix: rules are checked by ascending priority; the first whose condition holds is used. */
export default function RulesPanel({ templateKey, templateKeys = [] }: Props) {
  const { message } = AntApp.useApp()
  const [form] = Form.useForm<RuleFormValues>()
  const [rules, setRules] = useState<DecisionRule[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)

  const load = () => {
    setLoading(true)
    listRules(templateKey)
      .then(setRules)
      .catch((err) => message.error(problemMessage(err, 'Failed to load rules')))
      .finally(() => setLoading(false))
  }

  useEffect(load, [templateKey])

  const onSave = async () => {
    const v = await form.validateFields()
    const body: CreateDecisionRuleRequest = {
      templateKey: templateKey ?? v.templateKey,
      priority: v.priority,
      amountMin: v.amountMin ?? null,
      amountMax: v.amountMax ?? null,
      fieldMatches: parseFieldMatches(v.fieldMatches),
      expression: v.expression?.trim() || undefined,
      approvers: v.approvers.map((a) => ({ type: a.type, id: a.id.trim(), round: a.round })),
    }
    setSaving(true)
    try {
      await createRule(body)
      message.success('Rule created')
      setOpen(false)
      form.resetFields()
      load()
    } catch (err) {
      message.error(problemMessage(err, 'Create rule failed'))
    } finally {
      setSaving(false)
    }
  }

  const columns: ColumnsType<DecisionRule> = [
    { title: 'Priority', dataIndex: 'priority', width: 90, sorter: (a, b) => a.priority - b.priority },
    ...(templateKey ? [] : [{ title: 'Template', dataIndex: 'templateKey' } as const]),
    { title: 'Condition', render: (_, r) => describeCondition(r) },
    {
      title: 'Approvers (by round)',
      render: (_, r) => (
        <Space direction="vertical" size={4}>
          {groupByRound(r.approvers).map((round, index) => (
            <Space key={`${r.id}-r${index + 1}`} wrap>
              <Typography.Text type="secondary">Round {index + 1}</Typography.Text>
              {round.map((a) => (
                <Tag key={`${a.type}:${a.id}`}>
                  {a.type} · {a.id}
                </Tag>
              ))}
            </Space>
          ))}
        </Space>
      ),
    },
  ]

  return (
    <>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          New rule
        </Button>
        <Typography.Text type="secondary">Lower priority number is checked first.</Typography.Text>
      </Space>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={rules}
        loading={loading}
        pagination={{ pageSize: 10 }}
      />

      <Modal
        title="New approval rule"
        open={open}
        width={640}
        confirmLoading={saving}
        onOk={onSave}
        onCancel={() => setOpen(false)}
        okText="Create"
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ templateKey, priority: 100, approvers: [{ round: 1, type: 'USER', id: '' }] }}
        >
          {!templateKey && (
            <Form.Item name="templateKey" label="Template" rules={[{ required: true }]}>
              <Select options={templateKeys.map((k) => ({ value: k, label: k }))} />
            </Form.Item>
          )}
          <Form.Item name="priority" label="Priority" rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Space align="start" style={{ display: 'flex' }}>
            <Form.Item name="amountMin" label="Amount from (inclusive)">
              <InputNumber min={0} style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="amountMax" label="Amount below (exclusive)">
              <InputNumber min={0} style={{ width: 200 }} />
            </Form.Item>
          </Space>
          <Form.Item name="fieldMatches" label="Field equals (one field=value per line)">
            <Input.TextArea rows={2} placeholder="department=finance" />
          </Form.Item>
          <Form.Item
            name="expression"
            label="Expression (optional)"
            extra="e.g. amount > 5000 && region in [&quot;EU&quot;, &quot;US&quot;]"
          >
            <Input />
          </Form.Item>

          <Typography.Text strong>Approvers</Typography.Text>
          <Form.List name="approvers">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => (
                  <Space key={field.key} align="baseline" style={{ display: 'flex' }}>
                    <Form.Item name={[field.name, 'round']} rules={[{ required: true }]}>
                      <InputNumber min={1} max={5} addonBefore="Round" style={{ width: 130 }} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'type']} rules={[{ required: true }]}>
                      <Select style={{ width: 200 }} options={APPROVER_TYPES} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'id']} rules={[{ required: true, message: 'id' }]}>
                      <Input placeholder="id" />
                    </Form.Item>
                    {fields.length > 1 && <MinusCircleOutlined onClick={() => remove(field.name)} />}
                  </Space>
                ))}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ round: 1, type: 'USER', id: '' })}>
                  Add approver
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>
    </>
  )
}
