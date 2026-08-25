import { apiRequest } from '../api/client'
import { getAccessToken, setTokens, subscribeToAuth } from '../auth/authStore'
import { describe, expect, it, vi } from 'vitest'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const tokens = {
  accessToken: 'old-access',
  refreshToken: 'single-use-refresh',
  tokenType: 'Bearer',
  expiresInSeconds: 300,
}

describe('authenticated API client', () => {
  it('refreshes once and retries the original request once', async () => {
    setTokens(tokens)
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json({ code: 'UNAUTHENTICATED', status: 401 }, 401))
      .mockResolvedValueOnce(json({ ...tokens, accessToken: 'new-access', refreshToken: 'new-refresh' }))
      .mockResolvedValueOnce(json({ value: 'retried' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiRequest<{ value: string }>('/test')).resolves.toEqual({ value: 'retried' })
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/auth/refresh')
    expect(new Headers(fetchMock.mock.calls[2]?.[1]?.headers).get('Authorization')).toBe('Bearer new-access')
  })

  it('clears tokens after the retried request also returns 401', async () => {
    setTokens(tokens)
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json({ code: 'UNAUTHENTICATED', status: 401 }, 401))
      .mockResolvedValueOnce(json({ ...tokens, accessToken: 'new-access', refreshToken: 'new-refresh' }))
      .mockResolvedValueOnce(json({ code: 'UNAUTHENTICATED', status: 401 }, 401))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiRequest('/test')).rejects.toThrow('Your session has expired')
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(getAccessToken()).toBeNull()
  })

  it('uses one refresh for three concurrent 401 responses and retries all requests', async () => {
    setTokens(tokens)
    let refreshCalls = 0
    let oldTokenCalls = 0
    let newTokenCalls = 0
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      if (input === '/api/v1/auth/refresh') {
        refreshCalls += 1
        return json({ ...tokens, accessToken: 'new-access', refreshToken: 'new-refresh' })
      }
      const authorization = new Headers(init?.headers).get('Authorization')
      if (authorization === 'Bearer old-access') {
        oldTokenCalls += 1
        return json({ code: 'UNAUTHENTICATED', status: 401 }, 401)
      }
      newTokenCalls += 1
      return json({ path: input })
    })
    vi.stubGlobal('fetch', fetchMock)

    const results = await Promise.all([
      apiRequest('/one'),
      apiRequest('/two'),
      apiRequest('/three'),
    ])

    expect(results).toHaveLength(3)
    expect(refreshCalls).toBe(1)
    expect(oldTokenCalls).toBe(3)
    expect(newTokenCalls).toBe(3)
  })

  it('clears tokens once when the shared refresh fails', async () => {
    setTokens(tokens)
    let changes = 0
    const unsubscribe = subscribeToAuth(() => { changes += 1 })
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      if (input === '/api/v1/auth/refresh') {
        return json({ code: 'INVALID_CREDENTIALS', status: 401 }, 401)
      }
      return json({ code: 'UNAUTHENTICATED', status: 401 }, 401)
    })
    vi.stubGlobal('fetch', fetchMock)

    const results = await Promise.allSettled([
      apiRequest('/one'),
      apiRequest('/two'),
      apiRequest('/three'),
    ])
    unsubscribe()

    expect(results.every((result) => result.status === 'rejected')).toBe(true)
    expect(fetchMock.mock.calls.filter(([input]) => input === '/api/v1/auth/refresh')).toHaveLength(1)
    expect(changes).toBe(1)
    expect(getAccessToken()).toBeNull()
  })
})
