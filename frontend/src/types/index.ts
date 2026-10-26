export type ApprovalStatus =
  | 'DRAFT'
  | 'RUNNING'
  | 'APPROVED'
  | 'REJECTED'
  | 'WITHDRAWN'
  | 'TERMINATED'

export interface UserProfile {
  id: string
  name: string
  email?: string
}

export interface ApprovalInstance {
  id: string
  businessKey: string
  templateKey: string
  status: ApprovalStatus
  initiatorType: string
  initiatorId: string
  amount?: number
  currency?: string
  createdAt: string
  definitionVersion?: number
}

export interface ApprovalTask {
  id: string
  instanceId: string
  name: string
  assigneeType: string
  assigneeId: string
  /** Deadline set by the template's SLA, if any (ISO-8601). */
  dueAt?: string | null
  status: string
  version: number
}

export interface CreateApprovalRequest {
  templateKey: string
  externalBusinessKey: string
  /** Optional "start on behalf of" — honoured by the server only for SYSTEM callers. */
  initiatorType?: string
  initiatorId?: string
  variables: Record<string, unknown>
  callbackContext?: Record<string, unknown>
}

/** The acting user is taken from the access token server-side; only an optional comment is sent. */
export interface TaskActionRequest {
  comment?: string
}

export interface Approver {
  type: string
  id: string
  round?: number | null
}

/** Dry-run of approver resolution: the principals per round, or why a real start would go to manual review. */
export interface ApproverPreview {
  approvers: Approver[]
  rounds: Approver[][]
  problems: string[]
}

export type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED'

export interface Template {
  id: string
  templateKey: string
  status: TemplateStatus
  latestVersion: number
}

export interface TemplateDetail extends Template {
  draftBpmn: string | null
  /** JSON Schema as a JSON string. */
  draftFormSchema: string | null
}

export interface TemplateVersion {
  version: number
  checksum: string
  publishedAt: string
  publishedByType: string
  publishedById: string
}

export interface TemplateVersionDetail {
  version: number
  checksum: string
  bpmnXml: string
  formSchema: string | null
}

export interface PublishedForm {
  templateKey: string
  version: number
  formSchema: string | null
}

export type OverdueAction = 'NOTIFY_ONLY' | 'ESCALATE_TO_MANAGER' | 'ESCALATE_TO_USER'

export interface SlaPolicy {
  templateKey: string
  dueAfterHours: number
  remindBeforeHours: number | null
  onOverdue: OverdueAction
  escalateToUser: string | null
}

export interface SaveSlaRequest {
  dueAfterHours: number
  remindBeforeHours?: number | null
  onOverdue: OverdueAction
  escalateToUser?: string | null
}

export interface DecisionRule {
  id: string
  templateKey: string
  priority: number
  amountMin: number | null
  amountMax: number | null
  fieldMatches: Record<string, string> | null
  expression: string | null
  approvers: Approver[]
}

export interface CreateDecisionRuleRequest {
  templateKey: string
  priority: number
  amountMin?: number | null
  amountMax?: number | null
  fieldMatches?: Record<string, string>
  expression?: string
  approvers: Approver[]
}

export interface Delegation {
  id: string
  delegatorId: string
  delegateId: string
  startsAt: string
  endsAt: string
  active: boolean
}

export interface AuditRecord {
  id: string
  tenantId: string
  instanceId: string
  taskId: string | null
  actorType: string
  actorId: string
  action: string
  comment: string | null
  occurredAt: string
}

export interface WebhookDelivery {
  id: string
  eventId: string
  tenantId: string
  endpoint: string
  attempt: number
  status: string
  createdAt: string
  nextRetryAt: string | null
}
