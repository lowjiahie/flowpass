import http from '@/api/http'
import type {
  ApprovalInstance,
  ApprovalTask,
  CreateApprovalRequest,
  TaskActionRequest,
} from '@/types'

export async function listInstances(): Promise<ApprovalInstance[]> {
  const { data } = await http.get<ApprovalInstance[]>('/v1/approval-instances')
  return data
}

export async function listTodoTasks(): Promise<ApprovalTask[]> {
  // "mine": tasks assigned to me, or to a role I hold (the server decides who I am from the token).
  const { data } = await http.get<ApprovalTask[]>('/v1/tasks', { params: { mine: true, status: 'PENDING' } })
  return data
}

export async function listTasksByInstance(instanceId: string): Promise<ApprovalTask[]> {
  const { data } = await http.get<ApprovalTask[]>('/v1/tasks', {
    params: { instanceId },
  })
  return data
}

export async function getInstance(id: string): Promise<ApprovalInstance> {
  const { data } = await http.get<ApprovalInstance>(`/v1/approval-instances/${id}`)
  return data
}

export async function createInstance(
  req: CreateApprovalRequest,
): Promise<ApprovalInstance> {
  const { data } = await http.post<ApprovalInstance>('/v1/approval-instances', req)
  return data
}

export async function approveTask(
  taskId: string,
  req: TaskActionRequest,
): Promise<ApprovalInstance> {
  const { data } = await http.post<ApprovalInstance>(`/v1/tasks/${taskId}/approve`, req)
  return data
}

export async function rejectTask(
  taskId: string,
  req: TaskActionRequest,
): Promise<ApprovalInstance> {
  const { data } = await http.post<ApprovalInstance>(`/v1/tasks/${taskId}/reject`, req)
  return data
}

/** Withdraw a running instance — allowed for its initiator only (enforced server-side). */
export async function withdrawInstance(
  instanceId: string,
  req: TaskActionRequest,
): Promise<ApprovalInstance> {
  const { data } = await http.post<ApprovalInstance>(`/v1/approval-instances/${instanceId}/withdraw`, req)
  return data
}
