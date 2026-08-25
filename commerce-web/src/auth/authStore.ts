import type { TokenPair } from '../api/types'

const ACCESS_TOKEN_KEY = 'commerce.accessToken'

let accessToken = sessionStorage.getItem(ACCESS_TOKEN_KEY)
let refreshToken: string | null = null
const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

export function setTokens(tokens: TokenPair) {
  accessToken = tokens.accessToken
  refreshToken = tokens.refreshToken
  sessionStorage.setItem(ACCESS_TOKEN_KEY, tokens.accessToken)
  emit()
}

export function clearTokens() {
  if (accessToken === null && refreshToken === null) {
    return
  }
  accessToken = null
  refreshToken = null
  sessionStorage.removeItem(ACCESS_TOKEN_KEY)
  emit()
}

export function getAccessToken() {
  return accessToken
}

export function getRefreshToken() {
  return refreshToken
}

export function isAuthenticated() {
  return accessToken !== null
}

export function subscribeToAuth(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function resetAuthForTests() {
  accessToken = null
  refreshToken = null
  sessionStorage.removeItem(ACCESS_TOKEN_KEY)
  emit()
}
