/** Only normalize browser requests from this HTTP development server itself. */
export function isSameOriginDevRequest(origin: string | undefined, host: string | undefined): boolean {
  if (!origin || !host) return false
  try {
    const source = new URL(origin)
    const server = new URL(`http://${host}`)
    return source.origin === origin && source.origin === server.origin
  } catch {
    return false
  }
}
