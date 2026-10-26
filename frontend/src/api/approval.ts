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

/** Hand one of my open tasks to another user (server checks I am the assignee / hold the role). */
export async function transferTask(taskId: string, to: string, comment?: string): Promise<ApprovalTask> {
  const { data } = await http.post<ApprovalTask>(`/v1/tasks/${taskId}/transfer`, { to, comment })
  return data
}

/** Add-sign: ask another user to approve this round as well. Returns the task opened for them. */
export async function addApprover(taskId: string, userId: string, comment?: string): Promise<ApprovalTask> {
  const { data } = await http.post<ApprovalTask>(`/v1/tasks/${taskId}/add-approver`, { userId, comment })
  return data
}

/** Send the request back to the previous approval round. */
export async function returnTask(taskId: string, comment?: string): Promise<ApprovalInstance> {
  const { data } = await http.post<ApprovalInstance>(`/v1/tasks/${taskId}/return`, { comment })
  return data
}
