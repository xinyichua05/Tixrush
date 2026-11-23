import { tokenStore } from '../auth/tokenStore'
import type { ProblemDetail } from './types'

/** Any failed API call. The pages check `status` to decide what to show. */
export class ApiError extends Error {
  readonly status: number // 0 = the server couldn't be reached
  readonly fieldErrors: Record<string, string>
  readonly retryAfterSeconds: number | null // from the Retry-After header (429)

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}, retryAfterSeconds: number | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.retryAfterSeconds = retryAfterSeconds
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  headers?: Record<string, string>
}

/**
 * One wrapper around fetch() for every API call:
 *  - sends/receives JSON (or sends a FormData as-is, for file uploads)
 *  - adds "Authorization: Bearer <token>" when logged in
 *  - turns error responses into an ApiError with the backend's message
 *  - a 401 on a request that HAD a token = the token expired -> log out everywhere
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}, isRetry = false): Promise<T> {
  const token = tokenStore.get()
  const headers: Record<string, string> = { ...options.headers }
  if (token) headers.Authorization = `Bearer ${token}`
  // A FormData (file upload) goes as-is: the browser sets "multipart/form-data; boundary=..." itself.
  const isForm = options.body instanceof FormData
  if (options.body !== undefined && !isForm) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : isForm ? (options.body as FormData) : JSON.stringify(options.body),
    })
  } catch {
    throw new ApiError(0, "We can't connect right now. Please check your internet and try again.")
  }

  const text = await response.text()
  const data: unknown = text ? safeJson(text) : null

  if (!response.ok) {
    if (response.status === 401 && token) {
      // Our saved login is no longer valid (expired). Forget it, but only if nobody logged in
      // again meanwhile, then try once more as a visitor: public pages still load fine.
      if (tokenStore.get() === token) tokenStore.clear()
      if (!isRetry) return apiFetch<T>(path, options, true)
    }
    const problem = (typeof data === 'object' && data !== null ? data : {}) as ProblemDetail
    const retryAfter = response.headers.get('Retry-After')
    throw new ApiError(
      response.status,
      problem.detail || fallbackMessage(response.status),
      problem.errors ?? {},
      retryAfter ? Number(retryAfter) : null,
    )
  }
  return data as T
}

/** Plain words for errors that came without a message from the server. */
function fallbackMessage(status: number): string {
  if (status === 401) return 'Please log in to continue.'
  if (status === 403) return "Sorry, you can't do that."
  if (status === 404) return "We couldn't find that."
  if (status >= 500) return 'Something went wrong on our side. Please try again.'
  return 'Something went wrong. Please try again.'
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}
