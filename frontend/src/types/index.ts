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
  initiator: string
  amount?: number
  currency?: string
  createdAt: string
  definitionVersion?: number
}

export interface ApprovalTask {
  id: string
  instanceId: string
  name: string
  assignee: string
  status: string
  createdAt: string
}
