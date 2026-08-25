import { type ReactNode, useMemo, useState } from 'react'
import type { Quote } from '../api/types'
import { CheckoutContext } from './checkoutState'

export function CheckoutProvider({ children }: { children: ReactNode }) {
  const [quote, setQuote] = useState<Quote | null>(null)
  const value = useMemo(() => ({ quote, setQuote }), [quote])
  return <CheckoutContext.Provider value={value}>{children}</CheckoutContext.Provider>
}
