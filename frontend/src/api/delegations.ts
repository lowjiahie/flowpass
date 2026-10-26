import http from '@/api/http'
import type { Delegation } from '@/types'

export async function createDelegation(
  delegateId: string,
  endsAt: string,
  startsAt?: string,
): Promise<Delegation> {
  const { data } = await http.post<Delegation>('/v1/delegations', { delegateId, startsAt, endsAt })
  return data
}

export async function listMyDelegations(): Promise<Delegation[]> {
  const { data } = await http.get<Delegation[]>('/v1/delegations')
  return data
}

export async function revokeDelegation(id: string): Promise<void> {
  await http.delete(`/v1/delegations/${id}`)
}
