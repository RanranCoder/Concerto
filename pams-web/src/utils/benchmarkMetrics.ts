/** Legacy and imported evaluation rows may have no metadata or a non-object JSON value. */
export function readBenchmarkMetrics(raw?: string | null): Record<string, unknown> {
  try {
    const value: unknown = raw ? JSON.parse(raw) : null
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
  } catch { return {} }
}
