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
}
