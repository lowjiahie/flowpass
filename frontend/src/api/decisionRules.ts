import http from '@/api/http'
import type { ApproverPreview, CreateDecisionRuleRequest, DecisionRule } from '@/types'

/**
 * Dry run of the approval-matrix resolution: who would be asked, round by round, or why a real start would go
 * to manual review. The rules are evaluated server-side (single source of truth), never re-implemented here.
 */
export async function previewApprovers(
  templateKey: string,
  variables: Record<string, unknown>,
): Promise<ApproverPreview> {
  const { data } = await http.post<ApproverPreview>('/v1/admin/decision-rules/preview', {
    templateKey,
    variables,
  })
  return data
}

export async function listRules(templateKey?: string): Promise<DecisionRule[]> {
  const { data } = await http.get<DecisionRule[]>('/v1/admin/decision-rules', {
    params: templateKey ? { templateKey } : undefined,
  })
  return data
}

export async function createRule(req: CreateDecisionRuleRequest): Promise<DecisionRule> {
  const { data } = await http.post<DecisionRule>('/v1/admin/decision-rules', req)
  return data
}
