import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import { resetAuthForTests } from '../auth/authStore'
import { resetRefreshForTests } from '../api/client'

afterEach(() => {
  cleanup()
  resetAuthForTests()
  resetRefreshForTests()
  sessionStorage.clear()
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
