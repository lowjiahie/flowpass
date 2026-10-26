import http from '@/api/http'
import type {
  PublishedForm,
  SaveSlaRequest,
  SlaPolicy,
  Template,
  TemplateDetail,
  TemplateVersion,
  TemplateVersionDetail,
} from '@/types'

const BASE = '/v1/admin/templates'

export async function listTemplates(): Promise<Template[]> {
  const { data } = await http.get<Template[]>(BASE)
  return data
}

export async function getTemplate(key: string): Promise<TemplateDetail> {
  const { data } = await http.get<TemplateDetail>(`${BASE}/${key}`)
  return data
}

export async function createTemplate(templateKey: string): Promise<Template> {
  const { data } = await http.post<Template>(BASE, { templateKey })
  return data
}

export async function saveDraft(key: string, bpmnXml: string): Promise<TemplateDetail> {
  const { data } = await http.put<TemplateDetail>(`${BASE}/${key}/draft`, { bpmnXml })
  return data
}

/** `null` clears the draft form schema. */
export async function saveFormSchema(
  key: string,
  formSchema: Record<string, unknown> | null,
): Promise<TemplateDetail> {
  if (formSchema === null) {
    const { data } = await http.delete<TemplateDetail>(`${BASE}/${key}/draft/form-schema`)
    return data
  }
  const { data } = await http.put<TemplateDetail>(`${BASE}/${key}/draft/form-schema`, { formSchema })
  return data
}

export async function publishTemplate(key: string): Promise<Template> {
  const { data } = await http.post<Template>(`${BASE}/${key}/publish`)
  return data
}

export async function disableTemplate(key: string): Promise<Template> {
  const { data } = await http.post<Template>(`${BASE}/${key}/disable`)
  return data
}

export async function enableTemplate(key: string): Promise<Template> {
  const { data } = await http.post<Template>(`${BASE}/${key}/enable`)
  return data
}

export async function listVersions(key: string): Promise<TemplateVersion[]> {
  const { data } = await http.get<TemplateVersion[]>(`${BASE}/${key}/versions`)
  return data
}

export async function getVersion(key: string, version: number): Promise<TemplateVersionDetail> {
  const { data } = await http.get<TemplateVersionDetail>(`${BASE}/${key}/versions/${version}`)
  return data
}

/** The SLA of a template; `null` when none is set. */
export async function getSla(key: string): Promise<SlaPolicy | null> {
  const { data } = await http.get<SlaPolicy>(`${BASE}/${key}/sla`, {
    validateStatus: (s) => s === 200 || s === 404,
  })
  return data && 'dueAfterHours' in data ? data : null
}

export async function saveSla(key: string, req: SaveSlaRequest): Promise<SlaPolicy> {
  const { data } = await http.put<SlaPolicy>(`${BASE}/${key}/sla`, req)
  return data
}

export async function deleteSla(key: string): Promise<void> {
  await http.delete(`${BASE}/${key}/sla`)
}

/** The published form of a template (what the new-request screen renders). */
export async function getPublishedForm(key: string): Promise<PublishedForm> {
  const { data } = await http.get<PublishedForm>(`/v1/templates/${key}/form`)
  return data
}
