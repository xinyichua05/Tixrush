import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStore } from '../auth/tokenStore'
import { fakeToken } from '../test/setup'
import { ApiError, apiFetch } from './client'

function mockFetch(body: unknown, init: ResponseInit) {
  // A fresh Response per call: a body can only be read once, and 401s are retried.
  const fn = vi.fn().mockImplementation(async () => new Response(body === null ? null : JSON.stringify(body), init))
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('apiFetch', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the Bearer token when logged in', async () => {
    tokenStore.set(fakeToken())
    const fetchMock = mockFetch([], { status: 200 })

    await apiFetch('/api/events')

    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>
    expect(headers.Authorization).toMatch(/^Bearer /)
  })

  it('sends a FormData (file upload) as-is, letting the browser set the multipart type', async () => {
    const fetchMock = mockFetch({ id: 1 }, { status: 200 })
    const form = new FormData()
    form.append('file', new Blob(['x'], { type: 'image/webp' }), 'picture.webp')

    await apiFetch('/api/events/1/image', { method: 'PUT', body: form })

    const init = fetchMock.mock.calls[0][1]
    expect(init.body).toBe(form)
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined()
  })

  it('turns a ProblemDetail into an ApiError with the backend message', async () => {
    mockFetch({ title: 'Conflict', status: 409, detail: 'Sorry, this show is sold out.' }, { status: 409 })

    await expect(apiFetch('/api/x')).rejects.toMatchObject({ status: 409, message: 'Sorry, this show is sold out.' })
  })

  it('reads Retry-After on 429 and field errors on 400', async () => {
    mockFetch({ detail: 'Too many' }, { status: 429, headers: { 'Retry-After': '37' } })
    await expect(apiFetch('/api/x')).rejects.toMatchObject({ status: 429, retryAfterSeconds: 37 })

    mockFetch({ detail: 'Validation failed', errors: { name: 'must not be blank' } }, { status: 400 })
    await expect(apiFetch('/api/x')).rejects.toMatchObject({ fieldErrors: { name: 'must not be blank' } })
  })

  it('logs out when the server says 401 to a logged-in request', async () => {
    tokenStore.set(fakeToken())
    mockFetch({ title: 'Unauthorized' }, { status: 401 })

    await expect(apiFetch('/api/orders/mine')).rejects.toBeInstanceOf(ApiError)
    expect(tokenStore.get()).toBeNull()
  })

  it('an expired login does not break public pages: forget it and try again as a visitor', async () => {
    tokenStore.set(fakeToken())
    const fetchMock = vi.fn()
      .mockImplementationOnce(async () => new Response(null, { status: 401 }))
      .mockImplementationOnce(async () => new Response('[]', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiFetch('/api/events')).resolves.toEqual([])
    expect(tokenStore.get()).toBeNull()
    const retryHeaders = fetchMock.mock.calls[1][1].headers as Record<string, string>
    expect(retryHeaders.Authorization).toBeUndefined()
  })

  it('reports status 0 when the backend is down', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(apiFetch('/api/events')).rejects.toMatchObject({ status: 0 })
  })
})
