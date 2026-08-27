import http from '@/api/http'
import type { ApprovalInstance, ApprovalTask } from '@/types'

// 骨架阶段使用 mock 数据，后端 /v1 就绪后替换为真实调用。
const USE_MOCK = true

const mockInstances: ApprovalInstance[] = [
  {
    id: 'appr_01J5',
    businessKey: 'PO-2026-000123',
    templateKey: 'purchase_request',
    status: 'RUNNING',
    initiator: 'bob',
    amount: 23500,
    currency: 'MYR',
    createdAt: '2026-08-15T06:30:00Z',
    definitionVersion: 3,
  },
  {
    id: 'appr_01J6',
    businessKey: 'EXP-2026-000088',
    templateKey: 'expense_claim',
    status: 'APPROVED',
    initiator: 'alice',
    amount: 1200,
    currency: 'MYR',
    createdAt: '2026-08-16T02:10:00Z',
    definitionVersion: 1,
  },
]

const mockTasks: ApprovalTask[] = [
  {
    id: 'task_01',
    instanceId: 'appr_01J5',
    name: 'Finance Manager Approval',
    assignee: 'alice',
    status: 'PENDING',
    createdAt: '2026-08-15T06:30:00Z',
  },
]

export async function listInstances(): Promise<ApprovalInstance[]> {
  if (USE_MOCK) return mockInstances
  const { data } = await http.get<ApprovalInstance[]>('/v1/approval-instances')
  return data
}

export async function listTodoTasks(): Promise<ApprovalTask[]> {
  if (USE_MOCK) return mockTasks
  const { data } = await http.get<ApprovalTask[]>('/v1/tasks')
  return data
}

export async function getInstance(id: string): Promise<ApprovalInstance> {
  if (USE_MOCK) return mockInstances.find((i) => i.id === id) ?? mockInstances[0]
  const { data } = await http.get<ApprovalInstance>(`/v1/approval-instances/${id}`)
  return data
}
