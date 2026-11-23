// Runs before every test file (see vite.config.ts -> test.setupFiles).
import '@testing-library/jest-dom/vitest' // adds matchers like toBeInTheDocument() / toBeDisabled()
import { cleanup } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { afterEach } from 'vitest'

afterEach(() => {
  cleanup() // unmount what the test rendered
  localStorage.clear() // forget any login token
})

/** A fake (unsigned) JWT. The browser never verifies signatures, it only reads the payload. */
export function fakeToken(claims: Partial<{ sub: string; email: string; role: 'USER' | 'ADMIN'; exp: number }> = {}) {
  const payload = { sub: '7', email: 'fan@example.com', role: 'USER', exp: Math.floor(Date.now() / 1000) + 3600, ...claims }
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `${b64({ alg: 'HS256' })}.${b64(payload)}.signature`
}

/** A fresh React Query client for one test: no retries (a failing fake should fail at once), no caching between tests. */
export function testQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } } })
}
