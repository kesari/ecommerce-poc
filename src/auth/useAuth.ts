import { useSyncExternalStore } from 'react'
import { isAuthenticated, subscribeToAuth } from './authStore'

export function useAuth() {
  return useSyncExternalStore(subscribeToAuth, isAuthenticated, isAuthenticated)
}
