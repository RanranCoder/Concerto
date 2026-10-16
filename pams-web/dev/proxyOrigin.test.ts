import { describe, expect, it } from 'vitest'
import { isSameOriginDevRequest } from './proxyOrigin'

describe('HTTP development proxy origin', () => {
  it.each(['10.64.25.205:5173', 'localhost:5173', '127.0.0.1:5173', '[::1]:5173'])(
    'recognizes same-origin browser access through %s', host => {
      expect(isSameOriginDevRequest(`http://${host}`, host)).toBe(true)
    },
  )
  it.each([
    'http://untrusted.example', 'http://10.64.25.205:5174', 'https://10.64.25.205:5173',
    'null', 'not-a-url', 'http://user@10.64.25.205:5173', 'http://10.64.25.205:5173/path',
  ])('leaves cross-origin or malformed Origin %s unchanged', origin => {
    expect(isSameOriginDevRequest(origin, '10.64.25.205:5173')).toBe(false)
  })
  it('leaves requests without Origin or Host unchanged', () => {
    expect(isSameOriginDevRequest(undefined, 'localhost:5173')).toBe(false)
    expect(isSameOriginDevRequest('http://localhost:5173', undefined)).toBe(false)
  })
})
