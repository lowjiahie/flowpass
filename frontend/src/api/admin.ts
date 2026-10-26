import http from '@/api/http'
import type { AuditRecord, WebhookDelivery } from '@/types'

export async function listAuditRecords(params: {
  instanceId?: string
  action?: string
  limit?: number
}): Promise<AuditRecord[]> {
  const { data } = await http.get<AuditRecord[]>('/v1/admin/audit-records', { params })
  return data
}

export async function listWebhookDeliveries(params: {
  status?: string
  limit?: number
}): Promise<WebhookDelivery[]> {
  const { data } = await http.get<WebhookDelivery[]>('/v1/admin/webhook-deliveries', { params })
  return data
}
