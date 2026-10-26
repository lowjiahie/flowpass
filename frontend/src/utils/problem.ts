interface ProblemBody {
  detail?: string
  errors?: { field?: string; message?: string }[]
}

/** The server's reason (RFC 7807 `detail`, plus any per-field `errors`) or a fallback. */
export function problemMessage(err: unknown, fallback: string): string {
  const body = (err as { response?: { data?: ProblemBody } }).response?.data
  if (!body) {
    const isHttp = (err as { isAxiosError?: boolean }).isAxiosError === true
    return err instanceof Error && !isHttp && err.message ? err.message : fallback
  }
  const fields = (body.errors ?? [])
    .map((e) => (e.field ? `${e.field}: ${e.message ?? ''}` : (e.message ?? '')))
    .filter(Boolean)
  const head = body.detail ?? fallback
  return fields.length > 0 ? `${head} (${fields.join('; ')})` : head
}

/** True when the server answered with this HTTP status. */
export function hasStatus(err: unknown, status: number): boolean {
  return (err as { response?: { status?: number } }).response?.status === status
}
