import http from '@/api/http'
import type { Approver } from '@/types'

/**
 * Dry run of the approval-matrix resolution: which approvers would this template + variables yield.
 * The rules are evaluated server-side (single source of truth), never re-implemented here.
 */
export async function previewApprovers(
  templateKey: string,
  variables: Record<string, unknown>,
): Promise<Approver[]> {
  const { data } = await http.post<Approver[]>('/v1/admin/decision-rules/preview', {
    templateKey,
    variables,
  })
  return data
}
