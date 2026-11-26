import { describe, expect, it } from 'vitest'
import { isSameOriginDevRequest } from './proxyOrigin'

describe('HTTP development proxy origin', () => {
  it.each(['concerto.test:3000', 'localhost:3000', '127.0.0.1:3000', '[::1]:3000'])(
    'recognizes same-origin browser access through %s', host => {
      expect(isSameOriginDevRequest(`http://${host}`, host)).toBe(true)
    },
  )
  it.each([
    'http://untrusted.example', 'http://concerto.test:3001', 'https://concerto.test:3000',
    'null', 'not-a-url', 'http://user@concerto.test:3000', 'http://concerto.test:3000/path',
  ])('leaves cross-origin or malformed Origin %s unchanged', origin => {
    expect(isSameOriginDevRequest(origin, 'concerto.test:3000')).toBe(false)
  })
  it('leaves requests without Origin or Host unchanged', () => {
    expect(isSameOriginDevRequest(undefined, 'localhost:3000')).toBe(false)
    expect(isSameOriginDevRequest('http://localhost:3000', undefined)).toBe(false)
  })
})
