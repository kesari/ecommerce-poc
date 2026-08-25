import { setTokens } from '../auth/authStore'
import { apiRequest } from './client'
import type { TokenPair } from './types'

type Credentials = {
  email: string
  password: string
}

async function authenticate(path: string, credentials: Credentials) {
  const tokens = await apiRequest<TokenPair>(path, {
    method: 'POST',
    body: JSON.stringify(credentials),
  }, false)
  setTokens(tokens)
  return tokens
}

export function login(credentials: Credentials) {
  return authenticate('/auth/login', credentials)
}

export function signup(credentials: Credentials) {
  return authenticate('/auth/signup', credentials)
}
