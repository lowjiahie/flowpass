import { Form, Input, InputNumber, Select, Switch } from 'antd'

/** The JSON Schema subset the platform accepts for forms (see the backend's safe subset). */
interface SchemaProperty {
  type?: string
  title?: string
  description?: string
  enum?: (string | number)[]
  minimum?: number
  maximum?: number
  minLength?: number
  maxLength?: number
  default?: unknown
}

export interface FormSchema {
  required?: string[]
  properties?: Record<string, SchemaProperty>
}

/** Parse the schema string the API returns; `null` when there is none or it is not usable. */
export function parseFormSchema(raw: string | null | undefined): FormSchema | null {
  if (!raw) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && 'properties' in parsed) return parsed as FormSchema
  } catch {
    // an unreadable schema is treated as "no form"; the server still validates the input
  }
  return null
}

/** Initial values declared by the schema (`default`). */
export function schemaDefaults(schema: FormSchema): Record<string, unknown> {
  const values: Record<string, unknown> = {}
  for (const [key, prop] of Object.entries(schema.properties ?? {})) {
    if (prop.default !== undefined) values[key] = prop.default
  }
  return values
}

interface Props {
  schema: FormSchema
  /** Form path the values live under, e.g. ['variables']. */
  namePrefix: string[]
}

/**
 * Renders form fields from a JSON Schema. Presentation only: the server validates the submitted values against
 * the same schema, so a field the renderer does not understand is still checked there.
 */
export default function SchemaFields({ schema, namePrefix }: Props) {
  const required = new Set(schema.required ?? [])
  return (
    <>
      {Object.entries(schema.properties ?? {}).map(([key, prop]) => {
        const label = prop.title ?? key
        const rules = required.has(key) ? [{ required: true, message: `${label} is required` }] : []
        const common = { key, name: [...namePrefix, key], label, extra: prop.description, rules }

        if (prop.enum && prop.enum.length > 0) {
          return (
            <Form.Item {...common}>
              <Select options={prop.enum.map((v) => ({ value: v, label: String(v) }))} />
            </Form.Item>
          )
        }
        if (prop.type === 'boolean') {
          return (
            <Form.Item {...common} valuePropName="checked">
              <Switch />
            </Form.Item>
          )
        }
        if (prop.type === 'number' || prop.type === 'integer') {
          return (
            <Form.Item {...common}>
              <InputNumber
                style={{ width: '100%' }}
                min={prop.minimum}
                max={prop.maximum}
                precision={prop.type === 'integer' ? 0 : undefined}
              />
            </Form.Item>
          )
        }
        return (
          <Form.Item {...common}>
            <Input minLength={prop.minLength} maxLength={prop.maxLength} />
          </Form.Item>
        )
      })}
    </>
  )
}
