import { clearTokens, getAccessToken, getRefreshToken, setTokens } from '../auth/authStore'
import type { Problem, TokenPair } from './types'

const API_ROOT = '/api/v1'

const errorMessages: Record<string, string> = {
  ADDRESS_NOT_FOUND: 'That delivery address is no longer available.',
  BASKET_VERSION_CHANGED: 'Your basket changed after this quote was created. Review it before checking out again.',
  BASKET_VERSION_CONFLICT: 'Your basket changed in another request. Please try again.',
  COUPON_ALREADY_APPLIED: 'A coupon is already active on this basket.',
  COUPON_INVALID: 'That coupon is not valid or is no longer active.',
  DOWNSTREAM_SERVICE_UNAVAILABLE: 'A service is temporarily unavailable. Please try again shortly.',
  EMAIL_ALREADY_REGISTERED: 'An account already exists for that email address.',
  INVALID_CREDENTIALS: 'The email or password is incorrect.',
  INVALID_REQUEST: 'Check the information you entered and try again.',
  ORDER_NOT_FOUND: 'That order could not be found.',
  PAYMENT_DECLINED: 'The test payment was declined. Choose another payment result and retry.',
  PRODUCT_INACTIVE: 'That product is not currently available.',
  PRODUCT_NOT_FOUND: 'That product could not be found.',
  QUOTE_BASKET_CHANGED: 'Your basket changed after this quote was created. Review it before checking out again.',
  QUOTE_EXPIRED: 'This checkout quote has expired. Generate a new quote to continue.',
  UNAUTHENTICATED: 'Your session has expired. Please sign in again.',
}

export class ApiError extends Error {
  readonly problem: Problem

  constructor(problem: Problem) {
    super(errorMessages[problem.code] ?? problem.detail ?? 'Something went wrong. Please try again.')
    this.name = 'ApiError'
    this.problem = problem
  }
}

let refreshPromise: Promise<void> | null = null

async function problemFrom(response: Response): Promise<Problem> {
  try {
    const body = await response.json() as Partial<Problem>
    return {
      status: body.status ?? response.status,
      code: body.code ?? `HTTP_${response.status}`,
      type: body.type,
      title: body.title,
      detail: body.detail,
      correlationId: body.correlationId,
    }
  } catch {
    return { status: response.status, code: `HTTP_${response.status}` }
  }
}

async function refreshAccessToken() {
  const currentRefreshToken = getRefreshToken()
  if (!currentRefreshToken) {
    throw new ApiError({ status: 401, code: 'UNAUTHENTICATED' })
  }
  const response = await fetch(`${API_ROOT}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: currentRefreshToken }),
  })
  if (!response.ok) {
    throw new ApiError(await problemFrom(response))
  }
  setTokens(await response.json() as TokenPair)
}

function singleFlightRefresh() {
  if (refreshPromise === null) {
    refreshPromise = refreshAccessToken()
      .catch((error: unknown) => {
        clearTokens()
        throw error
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

function headersFor(init: RequestInit, authenticated: boolean) {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (authenticated) {
    const token = getAccessToken()
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
  }
  return headers
}

async function send(path: string, init: RequestInit, authenticated: boolean) {
  return fetch(`${API_ROOT}${path}`, {
    ...init,
    headers: headersFor(init, authenticated),
  })
}

export async function apiRequest<T>(path: string, init: RequestInit = {}, authenticated = true): Promise<T> {
  let response = await send(path, init, authenticated)
  if (authenticated && response.status === 401) {
    await singleFlightRefresh()
    response = await send(path, init, true)
    if (response.status === 401) {
      clearTokens()
    }
  }
  if (!response.ok) {
    throw new ApiError(await problemFrom(response))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

export function resetRefreshForTests() {
  refreshPromise = null
}
