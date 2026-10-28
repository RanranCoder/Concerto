import { afterEach, describe, expect, it, vi } from 'vitest'
import { createRequestId } from './requestId'

afterEach(() => vi.unstubAllGlobals())

describe('createRequestId', () => {
  it('uses native UUID generation when available', () => {
    const randomUUID = vi.fn(() => 'ac3826a0-a199-4704-a69d-075bcf918210')
    vi.stubGlobal('crypto', { randomUUID })
    expect(createRequestId()).toBe('ac3826a0-a199-4704-a69d-075bcf918210')
    expect(randomUUID).toHaveBeenCalledOnce()
  })

  it('creates distinct valid UUIDs without randomUUID, as on ordinary HTTP origins', () => {
    const getRandomValues = vi.fn(globalThis.crypto.getRandomValues.bind(globalThis.crypto))
    vi.stubGlobal('crypto', { getRandomValues })
    const ids = Array.from({ length: 100 }, () => createRequestId())
    for (const id of ids) expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(new Set(ids).size).toBe(ids.length)
    expect(getRandomValues).toHaveBeenCalledTimes(ids.length)
  })
})
